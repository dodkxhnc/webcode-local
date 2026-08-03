package com.webcode.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.webcode.app.R
import com.webcode.app.local.DirectClient
import com.webcode.app.local.LocalEngine
import com.webcode.app.termux.BgService
import com.webcode.app.termux.FloatingChatService
import com.webcode.app.termux.PixelOverlay

/**
 * 本地直连模式设置：
 * 直接向模型供应商（DeepSeek Responses API）发起请求，不依赖任何中间服务器。
 * 工具循环在手机本地执行。
 */
class LocalSetupActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_local_setup)

        val baseUrlInput = findViewById<EditText>(R.id.llm_base_url)
        val modelInput = findViewById<EditText>(R.id.llm_model)
        val apiKeyInput = findViewById<EditText>(R.id.llm_api_key)
        val reasoningMode = findViewById<android.widget.Spinner>(R.id.reasoning_mode)
        val effortSpinner = findViewById<android.widget.Spinner>(R.id.effort_spinner)

        val modeLabels = listOf("自动", "思考", "非思考")
        val modeValues = listOf("auto", "auto", "none")
        val effortLabels = listOf("低", "中", "高")
        val effortValues = listOf("low", "medium", "high")
        reasoningMode.adapter = android.widget.ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, modeLabels
        )
        effortSpinner.adapter = android.widget.ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, effortLabels
        )
        val savedReasoning = LocalEngine.reasoningSetting(this)
        reasoningMode.setSelection(
            when (savedReasoning) {
                "none" -> 2
                "low", "medium", "high" -> 1
                else -> 0
            }
        )
        effortSpinner.setSelection(
            effortValues.indexOf(savedReasoning).coerceAtLeast(0)
        )
        val statusText = findViewById<TextView>(R.id.status_bootstrap)
        val statusNode = findViewById<TextView>(R.id.status_node)
        val statusAgent = findViewById<TextView>(R.id.status_agent)
        val statusBattery = findViewById<TextView>(R.id.status_battery)

        val (key, baseUrl, model) = LocalEngine.loadConfig(this)
        baseUrlInput.setText(baseUrl)
        modelInput.setText(model)
        apiKeyInput.setText(if (key.isNotEmpty()) "••••••••（已保存，修改请重新输入）" else "")
        statusText.text = "模式：直连 ${baseUrl}"
        statusNode.text = "模型：$model"
        statusAgent.text = "API Key：" + if (key.isNotEmpty()) "已配置" else "未配置"
        statusBattery.text = "电池优化：" +
            if (BgService.isBatteryOptimizationIgnored(this)) "已忽略" else "未忽略"
        refreshRuntimeStatus()

        findViewById<View>(R.id.save_llm_btn).setOnClickListener {
            var key2 = apiKeyInput.text.toString().trim()
            if (key2.startsWith("••••")) key2 = key
            if (key2.isEmpty()) {
                Toast.makeText(this, "请填写 API Key", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val reasoning = when (reasoningMode.selectedItemPosition) {
                2 -> "none"
                1 -> effortValues[effortSpinner.selectedItemPosition]
                else -> "auto"
            }
            LocalEngine.saveConfig(
                this, key2,
                baseUrlInput.text.toString().trim(),
                modelInput.text.toString().trim(),
                reasoning
            )
            Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show()
            statusAgent.text = "API Key：已配置"
        }

        findViewById<View>(R.id.install_btn).setOnClickListener {
            installRuntime()
        }
        findViewById<View>(R.id.install_node_btn).setOnClickListener {
            testConnection(baseUrlInput.text.toString().trim(), modelInput.text.toString().trim())
        }
        findViewById<View>(R.id.start_btn).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
        findViewById<View>(R.id.background_btn).setOnClickListener {
            if (!BgService.isBatteryOptimizationIgnored(this)) {
                BgService.requestBattery(this)
            }
            BgService.start(this)
            PixelOverlay.requestOverlayPermissionIfNeeded(this)
            statusBattery.text = "电池优化：已请求忽略"
        }
        findViewById<View>(R.id.float_btn).setOnClickListener {
            FloatingChatService.start(this)
        }

    }

    private fun refreshRuntimeStatus() {
        val statusNode = findViewById<TextView>(R.id.status_node)
        statusNode.text = "Linux 运行时：" +
            if (com.webcode.app.termux.LocalAgentManager.isInstalled(this)) {
                "已安装（bash 可用）"
            } else {
                "未安装（AI 无法执行 sh）"
            }
    }

    private fun log(msg: String) {
        runOnUiThread {
            val v = findViewById<TextView>(R.id.log_view)
            v.text = v.text.toString() + msg + "\n"
            if (v.text.length > 8000) {
                v.text = v.text.substring(v.text.length - 6000)
            }
        }
    }

    private fun installRuntime() {
        val btn = findViewById<View>(R.id.install_btn)
        btn.isEnabled = false
        Thread {
            try {
                com.webcode.app.termux.LocalAgentManager.installBootstrap(this) { log(it) }
                log("✓ 完成")
            } catch (e: Exception) {
                log("安装失败：${e.message}")
            } finally {
                runOnUiThread {
                    btn.isEnabled = true
                    refreshRuntimeStatus()
                }
            }
        }.start()
    }

    private fun testConnection(baseUrl: String, model: String) {
        val key = LocalEngine.loadConfig(this).first
        if (key.isEmpty()) {
            Toast.makeText(this, "请先保存 API Key", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "测试中…", Toast.LENGTH_SHORT).show()
        Thread {
            val client = DirectClient(key, baseUrl, model)
            val abort = java.util.concurrent.atomic.AtomicBoolean(false)
            val result = client.create(
                "ping，请回复：连接成功",
                "你是测试助手。",
                null,
                object : DirectClient.Listener {
                    override fun onEvent(ev: DirectClient.ResponseEvent) {}
                    override fun onError(message: String) {}
                },
                abort
            )
            runOnUiThread {
                if (result.completed) {
                    val text = result.response?.optString("output_text") ?: "OK"
                    Toast.makeText(this, "连接成功：${text.take(40)}", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "连接失败：${result.error}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }
}
