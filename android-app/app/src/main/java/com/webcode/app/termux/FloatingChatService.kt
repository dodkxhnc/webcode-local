package com.webcode.app.termux

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.TextView
import com.webcode.app.R
import com.webcode.app.local.DirectClient
import com.webcode.app.local.LocalEngine
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 小窗模式：悬浮气泡 → 小窗聊天。
 * 与主界面共用 LocalEngine 的会话与工具（web_search / run_command / 文件 / 设备信息），
 * 但为独立轻量会话。所有悬浮窗操作均容错，权限不足自动停止。
 */
class FloatingChatService : Service() {

    private var wm: WindowManager? = null
    private var bubbleView: View? = null
    private var panelView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var panelParams: WindowManager.LayoutParams? = null

    private val handler = Handler(Looper.getMainLooper())
    private var chatLog: TextView? = null
    private var scrollView: ScrollView? = null

    private var lastX = 0f
    private var lastY = 0f
    private var moved = false

    private val items = JSONArray()
    private val abort = AtomicBoolean(false)
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        showBubble()
    }

    override fun onDestroy() {
        abort.set(true)
        removeBubble()
        removePanel()
        super.onDestroy()
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun showBubble() {
        try {
            wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val w = 56.dp()
            val view = LayoutInflater.from(this).inflate(R.layout.float_bubble, null)

            val params = WindowManager.LayoutParams(
                w, w,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP or Gravity.START
            params.x = 24
            params.y = 24 * 6

            view.setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        lastX = event.rawX
                        lastY = event.rawY
                        moved = false
                        false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - lastX
                        val dy = event.rawY - lastY
                        if (kotlin.math.abs(dx) > 5 || kotlin.math.abs(dy) > 5) moved = true
                        if (moved) {
                            params.x += dx.toInt()
                            params.y += dy.toInt()
                            lastX = event.rawX
                            lastY = event.rawY
                            try {
                                wm!!.updateViewLayout(v, params)
                            } catch (e: Exception) {
                            }
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved) togglePanel()
                        true
                    }
                    else -> false
                }
            }

            wm!!.addView(view, params)
            bubbleView = view
            bubbleParams = params
        } catch (e: Exception) {
            stopSelf()
        }
    }

    private fun togglePanel() {
        if (panelView != null) removePanel() else showPanel()
    }

    private fun showPanel() {
        try {
            if (wm == null || bubbleView == null) return
            val w = 260.dp()
            val h = 340.dp()
            val view = LayoutInflater.from(this).inflate(R.layout.float_panel, null)
            val params = WindowManager.LayoutParams(
                w, h,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP or Gravity.START
            params.x = (bubbleParams?.x ?: 24) - 100
            params.y = bubbleParams?.y ?: 24

            chatLog = view.findViewById(R.id.float_chat_log)
            scrollView = view.findViewById(R.id.float_scroll)
            val input = view.findViewById<EditText>(R.id.float_input)
            val sendBtn = view.findViewById<ImageButton>(R.id.float_send)
            view.findViewById<View>(R.id.float_close).setOnClickListener { removePanel() }

            view.findViewById<View>(R.id.float_header).setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        lastX = event.rawX
                        lastY = event.rawY
                        false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x += (event.rawX - lastX).toInt()
                        params.y += (event.rawY - lastY).toInt()
                        lastX = event.rawX
                        lastY = event.rawY
                        try {
                            wm!!.updateViewLayout(view, params)
                        } catch (e: Exception) {
                        }
                        true
                    }
                    else -> false
                }
            }

            sendBtn.setOnClickListener {
                val text = input.text.toString().trim()
                if (text.isNotEmpty() && !running) {
                    appendLog("我", text)
                    input.setText("")
                    sendToModel(text)
                }
            }

            wm!!.addView(view, params)
            panelView = view
            panelParams = params
        } catch (e: Exception) {
            removePanel()
        }
    }

    private fun removeBubble() {
        try {
            bubbleView?.let { wm?.removeView(it) }
        } catch (e: Exception) {
        }
        bubbleView = null
    }

    private fun removePanel() {
        try {
            panelView?.let { wm?.removeView(it) }
        } catch (e: Exception) {
        }
        panelView = null
    }

    /* ============ 对话 ============ */
    private fun sendToModel(text: String) {
        if (!LocalEngine.isConfigured(this)) {
            appendLog("系统", "请先在设置页填写 API Key")
            return
        }
        running = true
        abort.set(false)
        items.put(DirectClient.messageItem("user", text))
        appendLog("系统", "思考中…")

        Thread {
            try {
                val (key, baseUrl, model) = LocalEngine.loadConfig(this)
                val client = DirectClient(key, baseUrl, model)
                val buf = StringBuilder()

                val listener = object : DirectClient.Listener {
                    override fun onEvent(ev: DirectClient.ResponseEvent) {
                        when (ev.type) {
                            "response.output_text.delta" -> {
                                buf.append(ev.data.optString("delta"))
                                handler.post { liveText(buf.toString()) }
                            }
                            "response.reasoning_text.delta" -> {
                                handler.post { appendLog("思考", ev.data.optString("delta")) }
                            }
                        }
                    }

                    override fun onError(message: String) {
                        handler.post { appendLog("系统", "错误：$message") }
                    }
                }

                val result = client.create(
                    items, SMALL_SYSTEM_PROMPT, SMALL_TOOLS, listener, abort,
                    LocalEngine.reasoningSetting(this).takeIf { it != "auto" }
                )
                if (abort.get()) {
                    handler.post { appendLog("系统", "已停止") }
                    running = false
                    return@Thread
                }

                if (result.completed && result.response != null) {
                    val resp = result.response!!
                    val output = resp.optJSONArray("output")
                    val calls = mutableListOf<Pair<JSONObject, String>>()
                    var finalText = ""
                    for (i in 0 until (output?.length() ?: 0)) {
                        val item = output!!.optJSONObject(i) ?: continue
                        when (item.optString("type")) {
                            "function_call" -> {
                                val callId = item.optString("call_id").ifEmpty { item.optString("id") }
                                items.put(DirectClient.functionCallItem(callId, item.optString("name"), item.optString("arguments")))
                                calls.add(item to callId)
                            }
                            "web_search_call" -> items.put(item)
                            "reasoning" -> items.put(item) // 思考内容必须原样回传
                            "message" -> {
                                val content = item.optJSONArray("content")
                                for (j in 0 until (content?.length() ?: 0)) {
                                    val c = content!!.optJSONObject(j)
                                    if (c?.optString("type") == "output_text") {
                                        finalText += c.optString("text")
                                    }
                                }
                            }
                        }
                    }

                    if (calls.isNotEmpty()) {
                        for ((call, callId) in calls) {
                            if (abort.get()) break
                            val name = call.optString("name")
                            val args = call.optString("arguments")
                            handler.post { appendLog("工具", "执行 $name …") }
                            val resultStr = runTool(name, args)
                            items.put(DirectClient.functionCallOutputItem(callId, resultStr))
                            handler.post { appendLog("工具", "$name → ${resultStr.take(200)}") }
                        }
                        handler.post { appendLog("系统", "工具完成，继续生成…") }
                        continueConversation()
                    } else if (finalText.isNotEmpty()) {
                        appendFinal(finalText)
                    } else {
                        handler.post { appendLog("系统", "（无文本输出）") }
                    }
                } else {
                    handler.post { appendLog("系统", "请求失败：${result.error ?: "未知错误"}") }
                }
            } catch (e: Exception) {
                handler.post { appendLog("系统", "异常：${e.message ?: e.javaClass.simpleName}") }
            } finally {
                running = false
            }
        }.start()
    }

    private fun continueConversation() {
        if (abort.get() || running) return
        Thread {
            try {
                val (key, baseUrl, model) = LocalEngine.loadConfig(this)
                val client = DirectClient(key, baseUrl, model)
                val buf = StringBuilder()
                val listener = object : DirectClient.Listener {
                    override fun onEvent(ev: DirectClient.ResponseEvent) {
                        if (ev.type == "response.output_text.delta") {
                            buf.append(ev.data.optString("delta"))
                            handler.post { liveText(buf.toString()) }
                        }
                    }

                    override fun onError(message: String) {
                        handler.post { appendLog("系统", "错误：$message") }
                    }
                }
                val result = client.create(
                    items, SMALL_SYSTEM_PROMPT, SMALL_TOOLS, listener, abort,
                    LocalEngine.reasoningSetting(this).takeIf { it != "auto" }
                )
                if (result.completed && result.response != null) {
                    val text = result.response!!.optString("output_text")
                    if (text.isNotEmpty()) appendFinal(text)
                } else if (result.error != null) {
                    handler.post { appendLog("系统", "请求失败：${result.error}") }
                }
            } catch (e: Exception) {
                handler.post { appendLog("系统", "异常：${e.message}") }
            } finally {
                running = false
            }
        }.start()
    }

    private fun runTool(name: String, argsJson: String): String {
        val args = try {
            JSONObject(argsJson)
        } catch (e: Exception) {
            JSONObject()
        }
        return try {
            when (name) {
                "device_info" ->
                    "设备：${Build.MANUFACTURER} ${Build.MODEL}\n系统：Android ${Build.VERSION.RELEASE}"
                "open_url" -> {
                    val url = args.optString("url")
                    handler.post {
                        try {
                            startActivity(
                                Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        } catch (e: Exception) {
                        }
                    }
                    "已打开浏览器: $url"
                }
                "run_command" -> {
                    // 小窗不弹审批，危险命令一律拒绝；安全命令直接执行
                    val command = args.optString("command", "")
                    if (command.isEmpty()) return "命令为空"
                    if (command.contains("rm ") || command.contains("mkfs") ||
                        command.contains("sudo") || command.contains("> /dev/sd") ||
                        command.contains("--force") || command.contains("--hard")
                    ) {
                        "危险命令，请在主界面确认后执行"
                    } else {
                        LocalAgentManager.runCommand(
                            this, command, null, (args.optInt("timeout", 30).coerceAtMost(60)) * 1000L
                        ).trim().ifEmpty { "（无输出）" }
                    }
                }
                "read_file", "write_file", "list_files" ->
                    "该工具请在主界面使用（小窗仅支持搜索/设备/网页/命令）"
                else -> "未知工具: $name"
            }
        } catch (e: Exception) {
            "执行失败: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun appendLog(role: String, text: String) {
        chatLog?.append("[$role] $text\n\n")
        scrollDown()
    }

    private fun liveText(text: String) {
        val tv = chatLog ?: return
        val prev = tv.text?.toString()?.substringBeforeLast("\n\n") ?: ""
        tv.text = prev + "\n\n" + text
        scrollDown()
    }

    private fun appendFinal(text: String) {
        handler.post {
            val tv = chatLog ?: return@post
            tv.text = tv.text?.toString()?.substringBeforeLast("\n\n") + "\n\n" + text
            scrollDown()
        }
    }

    private fun scrollDown() {
        scrollView?.post { scrollView?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun Int.dp(): Int =
        (this * resources.displayMetrics.density).toInt()

    companion object {
        private val SMALL_SYSTEM_PROMPT = """
            你是运行在用户 Android 手机小窗里的本地 AI 助手 "WebCode Local"。
            可用工具：web_search（联网搜索）、run_command（bash，危险命令会被拒绝）、device_info（设备信息）、open_url（打开网页）。
            回答使用中文，简洁。
        """.trimIndent()

        private val SMALL_TOOLS: List<JSONObject> = listOf(
            DirectClient.webSearchTool(),
            DirectClient.functionTool(
                "run_command", "执行 bash 命令（危险命令会被拒绝）",
                JSONObject()
                    .put("command", JSONObject().put("type", "string"))
                    .put("timeout", JSONObject().put("type", "integer")),
                listOf("command")
            ),
            DirectClient.functionTool(
                "device_info", "查看设备信息", JSONObject(), emptyList()
            ),
            DirectClient.functionTool(
                "open_url", "在手机浏览器打开网页",
                JSONObject().put("url", JSONObject().put("type", "string")),
                listOf("url")
            )
        )

        fun start(context: Context) {
            try {
                context.startService(Intent(context, FloatingChatService::class.java))
            } catch (e: Exception) {
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, FloatingChatService::class.java))
            } catch (e: Exception) {
            }
        }
    }
}
