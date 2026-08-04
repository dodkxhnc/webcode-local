package com.webcode.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.webcode.app.BuildConfig
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
    companion object {
        private const val REQ_PICK_ROOTFS = 2002
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_local_setup)
        // 必须在任何 TermuxRuntime 访问前初始化（否则 lateinit 未初始化闪退）
        com.webcode.app.termux.TermuxRuntime.init(this)
        findViewById<TextView>(R.id.title_version).text =
            "WebCode 本地模式 v${BuildConfig.VERSION_NAME}"

        val baseUrlInput = findViewById<EditText>(R.id.llm_base_url)
        val modelInput = findViewById<EditText>(R.id.llm_model)
        val apiKeyInput = findViewById<EditText>(R.id.llm_api_key)
        val reasoningMode = findViewById<android.widget.Spinner>(R.id.reasoning_mode)
        val effortSpinner = findViewById<android.widget.Spinner>(R.id.effort_spinner)

        val modeLabels = listOf("自动", "思考", "非思考")
        val modeValues = listOf("auto", "auto", "none")
        val effortLabels = listOf("低", "中", "高", "最高")
        val effortValues = listOf("low", "medium", "high", "max")
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
        val autoEnterCb = findViewById<android.widget.CheckBox>(R.id.auto_enter_cb)
        val rootfsCb = findViewById<android.widget.CheckBox>(R.id.rootfs_cb)
        autoEnterCb.isChecked = com.webcode.app.local.LocalEngine.autoEnter(this)
        rootfsCb.isChecked = com.webcode.app.local.LocalEngine.useRootfs(this)
        // 勾选立即保存（无需再点保存配置）
        autoEnterCb.setOnCheckedChangeListener { _, checked ->
            com.webcode.app.local.LocalEngine.setAutoEnter(this, checked)
        }
        rootfsCb.setOnCheckedChangeListener { _, checked ->
            com.webcode.app.local.LocalEngine.setRootfs(this, checked)
        }

        // ===== 外部路径挂载 =====
        val mountCb = findViewById<android.widget.CheckBox>(R.id.mount_cb)
        val mountPanel = findViewById<View>(R.id.mount_panel)
        val mountList = findViewById<TextView>(R.id.mount_list)
        mountCb.isChecked = com.webcode.app.local.LocalEngine.mountEnabled(this)
        mountPanel.visibility = if (mountCb.isChecked) View.VISIBLE else View.GONE
        mountCb.setOnCheckedChangeListener { _, checked ->
            com.webcode.app.local.LocalEngine.setMountEnabled(this, checked)
            mountPanel.visibility = if (checked) View.VISIBLE else View.GONE
            renderMounts()
            if (checked) requestAllFilesAccess()
        }
        findViewById<View>(R.id.add_mount_btn).setOnClickListener {
            addMountPath()
        }
        findViewById<TextView>(R.id.mount_list).setOnLongClickListener {
            val paths = com.webcode.app.local.LocalEngine.mountPaths(this)
            if (paths.isEmpty()) {
                Toast.makeText(this, "当前使用默认路径（内部存储），无需编辑", Toast.LENGTH_SHORT).show()
            } else {
                android.app.AlertDialog.Builder(this)
                    .setTitle("选择要编辑/删除的路径")
                    .setItems(paths.toTypedArray()) { _, which ->
                        editOrDeleteMount(paths[which], paths, which)
                    }
                    .show()
            }
            true
        }
        renderMounts()

        // ===== MCP 服务器管理 =====
        findViewById<View>(R.id.add_mcp_btn).setOnClickListener {
            addMcpServer()
        }
        renderMcpList()
        findViewById<TextView>(R.id.mcp_list).setOnLongClickListener {
            val servers = com.webcode.app.local.McpManager.servers(this)
            if (servers.isEmpty()) return@setOnLongClickListener true
            val names = servers.map { "${it.name} (${it.type})" }.toTypedArray()
            android.app.AlertDialog.Builder(this)
                .setTitle("长按删除 MCP 服务器")
                .setItems(names) { _, which ->
                    val list = servers.toMutableList()
                    list.removeAt(which)
                    com.webcode.app.local.McpManager.saveServers(this, list)
                    renderMcpList()
                }
                .show()
            true
        }

        // 勾选"自动进入对话"且已配置 → 直接进主界面（仅应用冷启动时生效；
        // 从聊天页点设置进入时跳过，保证设置页可访问）
        val skipAuto = intent?.getBooleanExtra("skip_auto_enter", false) ?: false
        if (!skipAuto &&
            com.webcode.app.local.LocalEngine.autoEnter(this) &&
            com.webcode.app.local.LocalEngine.isConfigured(this)
        ) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

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

        findViewById<View>(R.id.install_node_btn).setOnClickListener {
            testConnection(baseUrlInput.text.toString().trim(), modelInput.text.toString().trim())
        }
        findViewById<View>(R.id.proot_check_btn).setOnClickListener {
            prootCheck()
        }
        findViewById<View>(R.id.uninstall_rootfs_btn).setOnClickListener {
            confirmUninstallRootfs()
        }
        findViewById<View>(R.id.import_rootfs_btn).setOnClickListener {
            importRootfs()
        }
        findViewById<View>(R.id.download_rootfs_btn).setOnClickListener {
            downloadRootfs()
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
            // 必须先有悬浮窗权限，否则服务静默退出
            com.webcode.app.termux.PixelOverlay.requestOverlayPermissionIfNeeded(this)
            if (android.provider.Settings.canDrawOverlays(this)) {
                FloatingChatService.start(this)
                Toast.makeText(this, "小窗已开启：点击悬浮球展开", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "请在系统设置中允许悬浮窗后重新点击", Toast.LENGTH_LONG).show()
            }
        }

    }

    private fun renderMounts() {
        val list = findViewById<TextView>(R.id.mount_list)
        val paths = com.webcode.app.local.LocalEngine.mountPaths(this)
        list.text = if (paths.isEmpty()) {
            "默认：内部存储 /storage/emulated/0（挂载为 /mnt/external）\n（长按列表可编辑/删除，默认路径不可删）"
        } else {
            paths.mapIndexed { i, p ->
                val g = if (i == 0) "/mnt/external" else "/mnt/external-$i"
                "$g ← $p"
            }.joinToString("\n") + "\n\n（长按列表可编辑/删除）"
        }
    }

    private fun requestAllFilesAccess() {
        // 让 proot 进程能读写 /storage/emulated/0 全目录（API 30+ 需要"所有文件访问"权限）
        try {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                startActivity(
                    Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                        .setData(android.net.Uri.parse("package:$packageName"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        } catch (e: Exception) {
            try {
                startActivity(
                    Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e2: Exception) {
            }
        }
    }

    private fun editOrDeleteMount(path: String, all: List<String>, index: Int) {
        android.app.AlertDialog.Builder(this)
            .setTitle(path)
            .setItems(arrayOf("编辑", "删除")) { _, which ->
                when (which) {
                    0 -> {
                        val input = EditText(this)
                        input.setText(path)
                        android.app.AlertDialog.Builder(this)
                            .setTitle("编辑挂载路径")
                            .setView(input)
                            .setPositiveButton("保存") { _, _ ->
                                val newPath = input.text.toString().trim()
                                if (newPath.isNotEmpty()) {
                                    val cur = all.toMutableList()
                                    cur[index] = newPath
                                    com.webcode.app.local.LocalEngine.setMountPaths(this, cur)
                                    renderMounts()
                                }
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                    1 -> {
                        android.app.AlertDialog.Builder(this)
                            .setMessage("删除挂载路径 $path？")
                            .setPositiveButton("删除") { _, _ ->
                                val cur = all.toMutableList()
                                cur.removeAt(index)
                                com.webcode.app.local.LocalEngine.setMountPaths(this, cur)
                                renderMounts()
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                }
            }
            .show()
    }

    private fun addMountPath() {
        val input = EditText(this)
        input.hint = "例如 /storage/emulated/0/Download"
        android.app.AlertDialog.Builder(this)
            .setTitle("添加外部路径（Linux 绝对路径）")
            .setView(input)
            .setPositiveButton("添加") { _, _ ->
                val p = input.text.toString().trim()
                if (p.isNotEmpty()) {
                    val cur = com.webcode.app.local.LocalEngine.mountPaths(this).toMutableList()
                    if (!cur.contains(p)) cur.add(p)
                    com.webcode.app.local.LocalEngine.setMountPaths(this, cur)
                    renderMounts()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun renderMcpList() {
        val tv = findViewById<TextView>(R.id.mcp_list)
        val servers = com.webcode.app.local.McpManager.servers(this)
        tv.text = if (servers.isEmpty()) {
            "未配置 MCP 服务器。stdio 示例：npx -y @modelcontextprotocol/server-filesystem /workspace（需先在 Ubuntu 安装 nodejs）"
        } else {
            servers.joinToString("\n") { "${it.name} (${it.type}): ${it.command}" } +
                "\n\n（长按列表删除服务器）"
        }
    }

    private fun addMcpServer() {
        val nameInput = EditText(this)
        nameInput.hint = "服务器名称，如 files"
        val typeSpinner = android.widget.Spinner(this)
        typeSpinner.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        typeSpinner.adapter = android.widget.ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, listOf("stdio", "http")
        )
        val cmdInput = EditText(this)
        cmdInput.hint = "stdio: 命令（rootfs 内执行） 或 http: URL"

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(nameInput)
            addView(typeSpinner)
            addView(cmdInput)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("添加 MCP 服务器")
            .setView(layout)
            .setPositiveButton("添加") { _, _ ->
                val name = nameInput.text.toString().trim()
                val cmd = cmdInput.text.toString().trim()
                if (name.isNotEmpty() && cmd.isNotEmpty()) {
                    val servers = com.webcode.app.local.McpManager.servers(this).toMutableList()
                    servers.add(
                        com.webcode.app.local.McpServer(
                            name,
                            typeSpinner.selectedItem.toString(),
                            cmd
                        )
                    )
                    com.webcode.app.local.McpManager.saveServers(this, servers)
                    renderMcpList()
                    Toast.makeText(this, "MCP 服务器已添加", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun refreshRuntimeStatus() {
        val statusNode = findViewById<TextView>(R.id.status_node)
        statusNode.text = if (com.webcode.app.termux.TermuxRuntime.isRootfsInstalled()) {
            "Ubuntu 26.04 rootfs：已安装 ✓"
        } else {
            "Ubuntu rootfs：未安装（AI 无法执行 sh）"
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

    private fun downloadRootfs() {
        val btn = findViewById<View>(R.id.download_rootfs_btn)
        val wrap = findViewById<View>(R.id.progress_wrap)
        val bar = findViewById<android.widget.ProgressBar>(R.id.download_progress)
        val text = findViewById<TextView>(R.id.progress_text)
        btn.isEnabled = false
        wrap.visibility = View.VISIBLE
        bar.isIndeterminate = true
        text.text = "准备下载 rootfs…"

        var lastUpdate = 0L
        Thread {
            try {
                val msg = com.webcode.app.termux.LocalAgentManager.installRootfs(this) { done, total ->
                    if (done < 0) {
                        runOnUiThread {
                            bar.isIndeterminate = false
                            bar.progress = 0
                            text.text = "当前源失败，切换下载源…"
                        }
                    } else if (total > 0) {
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate > 150) {
                            lastUpdate = now
                            val pct = (done * 100 / total).toInt()
                            runOnUiThread {
                                bar.isIndeterminate = false
                                bar.max = total.toInt()
                                bar.progress = done.toInt()
                                text.text = "$pct% (${done / 1024 / 1024}/${total / 1024 / 1024}MB)"
                            }
                        }
                    }
                }
                log(msg)
                log("解压中（约 1 分钟）…")
                runOnUiThread {
                    findViewById<android.widget.CheckBox>(R.id.rootfs_cb).isChecked = true
                    com.webcode.app.local.LocalEngine.setRootfs(this, true)
                    refreshRuntimeStatus()
                }
                log("✓ Ubuntu 26.04 就绪")
            } catch (e: Exception) {
                log("rootfs 安装失败：${e.message}")
            } finally {
                runOnUiThread {
                    btn.isEnabled = true
                    wrap.visibility = View.GONE
                }
            }
        }.start()
    }

    private fun confirmUninstallRootfs() {
        if (!com.webcode.app.termux.TermuxRuntime.isRootfsInstalled()) {
            Toast.makeText(this, "Ubuntu rootfs 未安装", Toast.LENGTH_SHORT).show()
            return
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("卸载 Ubuntu rootfs？")
            .setMessage(
                "⚠️ 卸载将带来以下影响：\n\n" +
                "1. 删除整个 Ubuntu 系统（约 120MB）\n" +
                "2. 所有通过 apt 安装的软件将被清除（python、node 等）\n" +
                "3. /root 目录下保存的文件将被删除\n" +
                "4. AI 将无法执行任何 shell 命令（sh/apt/python）\n\n" +
                "★ 不会删除：应用工作区文件（files/workspace）、会话记录、模型配置\n\n" +
                "如需恢复，需重新下载 35MB 安装包并重新安装。"
            )
            .setPositiveButton("确认卸载") { _, _ ->
                Thread {
                    val ok = com.webcode.app.termux.TermuxRuntime.uninstallRootfs()
                    runOnUiThread {
                        if (ok) {
                            log("✓ Ubuntu rootfs 已卸载")
                            findViewById<android.widget.CheckBox>(R.id.rootfs_cb).isChecked = false
                            com.webcode.app.local.LocalEngine.setRootfs(this, false)
                            refreshRuntimeStatus()
                        } else {
                            log("卸载失败")
                        }
                    }
                }.start()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun importRootfs() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/gzip", "application/x-tar", "application/octet-stream"))
        }
        startActivityForResult(intent, REQ_PICK_ROOTFS)
    }

    private fun prootCheck() {
        Thread {
            try {
                log("--- proot 自检 ---")
                com.webcode.app.termux.TermuxRuntime.init(this)
                log("自动从 assets 安装 proot…")
                com.webcode.app.termux.TermuxRuntime.ensureProot()
                val prootBin = java.io.File(com.webcode.app.termux.TermuxRuntime.binDir, "proot")
                val arch = com.webcode.app.termux.TermuxRuntime.archName()
                log("架构: $arch")
                log("目标路径: " + prootBin.absolutePath)
                log("proot 存在: " + prootBin.exists() + " 可执行: " + (prootBin.exists() && prootBin.canExecute()) + " 大小: " + (if (prootBin.exists()) prootBin.length() else 0) + "B")
                val loader = java.io.File(com.webcode.app.termux.TermuxRuntime.prefixDir, "libexec/proot/loader")
                log("loader: " + loader.absolutePath)
                log("loader 存在: " + loader.exists() + " 可执行: " + (loader.exists() && loader.canExecute()))
                if (!prootBin.exists() || !prootBin.canExecute()) {
                    log("proot 组件异常，请查看 Android/data/com.webcode.app/files/proot-errors.log")
                    return@Thread
                }
                // 1) 直接运行 proot --version（绕过 rootfs 包装，隔离 proot 自身问题）
                log("1) 直接运行 proot --version:")
                try {
                    val pb = ProcessBuilder(prootBin.absolutePath, "--version")
                    pb.environment()["LD_LIBRARY_PATH"] =
                        com.webcode.app.termux.TermuxRuntime.prefixDir + "/lib"
                    pb.redirectErrorStream(true)
                    val p = pb.start()
                    val v = p.inputStream.readBytes().toString(Charsets.UTF_8)
                    p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)
                    log(v.trim().ifEmpty { "(无输出，退出码 ${p.exitValue()})" })
                } catch (e: Exception) {
                    log("proot 启动失败: ${e.message ?: e.javaClass.simpleName}")
                }
                // 2) proot 完整链路（rootfs 模式，先确保 rootfs）
                log("2) rootfs 完整链路:")
                if (!com.webcode.app.termux.TermuxRuntime.isRootfsInstalled()) {
                    log("   rootfs 未安装，跳过（先点「下载 Ubuntu 26.04 rootfs」）")
                } else {
                    val bash = java.io.File(com.webcode.app.termux.TermuxRuntime.rootfsDir, "bin/bash")
                    try {
                        val st = android.system.Os.stat(bash.absolutePath)
                        log("   /bin/bash 权限: ${Integer.toOctalString(st.st_mode and 0x1FF)} 可执行: ${bash.canExecute()}")
                    } catch (e: Exception) {
                        log("   无法读取 bash 权限: ${e.message}")
                    }
                    com.webcode.app.local.LocalEngine.setRootfs(this, true)
                    val r = com.webcode.app.termux.LocalAgentManager.runCommand(
                        this, "echo OK && pwd && which ls && cat /etc/os-release | head -2"
                    )
                    log(r.trim().ifEmpty { "(无输出)" })
                }
                log("--- 自检完成 ---")
            } catch (e: Exception) {
                log("自检异常: ${e.message}")
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
            if (!result.completed) {
                com.webcode.app.termux.DiagLog.log(this, "Net", "测试连接失败: ${result.error}")
            }
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
