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
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.webcode.app.R
import com.webcode.app.api.Part
import com.webcode.app.api.SessionMessage
import com.webcode.app.local.DirectClient
import com.webcode.app.local.LocalEngine
import com.webcode.app.ui.ChatAdapter
import com.webcode.app.ui.ChatListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 小窗模式：悬浮气泡 → 完整小窗。
 * 功能：聊天（与全屏一致的气泡 UI）/ 历史对话（分区分组）/ 新增对话 / 尺寸调节。
 * 与主界面共用会话存储。
 */
class FloatingChatService : Service(), ChatListener {

    private var wm: WindowManager? = null
    private var bubbleView: View? = null
    private var panelView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var panelParams: WindowManager.LayoutParams? = null

    private val handler = Handler(Looper.getMainLooper())
    private var chatList: RecyclerView? = null
    private var adapter: ChatAdapter? = null
    private var titleText: TextView? = null
    private var inputBox: EditText? = null

    private var lastX = 0f
    private var lastY = 0f
    private var moved = false

    private var sessionId: String? = null
    private var items = JSONArray()
    private val messages = mutableListOf<SessionMessage>()
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
        // 继承全屏对话：优先加载最近使用的会话（全屏正在用的除外）
        val last = LocalEngine.lastSession(this)
        val engine = LocalEngine.getInstance(appContext())
        if (last != null && engine?.floatGet(last) != null &&
            last != engine.runningSessionId()
        ) {
            loadSession(last)
        } else {
            newSession()
        }
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
            val w = 36.dp()
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

            view.isClickable = true
            view.setOnClickListener { togglePanel() }
            view.setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        lastX = event.rawX
                        lastY = event.rawY
                        moved = false
                        true
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
                        if (!moved) v.performClick()
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        moved = false
                        true
                    }
                    else -> false
                }
            }

            wm!!.addView(view, params)
            bubbleView = view
            bubbleParams = params
        } catch (e: Exception) {
            DiagLog.log(this, "Float", "showBubble 失败: ${e.message ?: e.javaClass.simpleName}")
            stopSelf()
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

    private fun togglePanel() {
        if (panelView != null) removePanel() else showPanel()
    }

    private fun showPanel() {
        try {
            if (wm == null || bubbleView == null) return
            val w = 280.dp()
            val h = 400.dp()
            val view = LayoutInflater.from(this).inflate(R.layout.float_panel, null)
            val params = WindowManager.LayoutParams(
                w, h,
                overlayType(),
                0, // 可聚焦：允许输入框获取焦点召唤键盘
                PixelFormat.TRANSLUCENT
            )
            params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            params.gravity = Gravity.TOP or Gravity.START
            // 面板定位：避免负坐标
            val screenW = resources.displayMetrics.widthPixels
            val screenH = resources.displayMetrics.heightPixels
            val bx = bubbleParams?.x ?: 24
            val by = bubbleParams?.y ?: 24
            var px = bx - 100
            if (px < 0) px = bx + 60
            params.x = px.coerceIn(0, (screenW - w - 8).coerceAtLeast(0))
            params.y = by.coerceIn(0, (screenH - h - 8).coerceAtLeast(0))

            chatList = view.findViewById(R.id.float_chat_list)
            titleText = view.findViewById(R.id.float_title)
            inputBox = view.findViewById(R.id.float_input)
            adapter = ChatAdapter(this, this, messages)
            chatList!!.layoutManager = LinearLayoutManager(this)
            chatList!!.adapter = adapter

            val menuOverlay = view.findViewById<View>(R.id.float_menu_overlay)
            view.findViewById<View>(R.id.float_close).setOnClickListener { removePanel() }
            view.findViewById<View>(R.id.float_menu).setOnClickListener {
                menuOverlay.visibility = if (menuOverlay.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
            view.findViewById<View>(R.id.float_opt_new).setOnClickListener {
                menuOverlay.visibility = View.GONE
                newSession()
            }
            view.findViewById<View>(R.id.float_opt_history).setOnClickListener {
                menuOverlay.visibility = View.GONE
                showHistoryInPanel()
            }
            menuOverlay.setOnClickListener { menuOverlay.visibility = View.GONE }

            view.findViewById<View>(R.id.float_send).setOnClickListener {
                val text = inputBox?.text.toString().trim()
                if (text.isNotEmpty() && !running) {
                    inputBox?.setText("")
                    sendToModel(text)
                }
            }

            view.findViewById<View>(R.id.float_header).setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        lastX = event.rawX
                        lastY = event.rawY
                        true
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
            setupResizeHandle(view)

            wm!!.addView(view, params)
            panelView = view
            panelParams = params
            renderLog()
        } catch (e: Exception) {
            DiagLog.log(this, "Float", "showPanel 失败: ${e.message ?: e.javaClass.simpleName}")
            removePanel()
        }
    }

    private fun setupResizeHandle(panel: View) {
        val handle = panel.findViewById<View>(R.id.float_resize)
        var startX = 0f
        var startY = 0f
        var startW = 0
        var startH = 0
        handle.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    val lp = panelParams
                    if (lp != null) {
                        startW = lp.width
                        startH = lp.height
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val lp = panelParams ?: return@setOnTouchListener true
                    val dx = (event.rawX - startX).toInt()
                    val dy = (event.rawY - startY).toInt()
                    lp.width = (startW + dx).coerceIn(200.dp(), 420.dp())
                    lp.height = (startH + dy).coerceIn(320.dp(), 640.dp())
                    try {
                        panelView?.let { wm?.updateViewLayout(it, lp) }
                    } catch (e: Exception) {
                    }
                    true
                }
                else -> false
            }
        }
    }

    /* ============ 会话管理 ============ */
    private fun newSession() {
        val engine = LocalEngine.getInstance(appContext()) ?: return
        // 当前会话为空时提示已在新的对话
        if (sessionId != null && items.length() == 0 && messages.isEmpty()) {
            android.widget.Toast.makeText(this, "已在新的对话", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val s = engine.floatCreate("小窗对话")
        loadSession(s.id)
    }

    private fun loadSession(id: String) {
        val engine = LocalEngine.getInstance(appContext()) ?: return
        // 全屏正在使用的会话禁止在小窗跳转
        val locked = engine.runningSessionId()
        if (id == locked) {
            android.widget.Toast.makeText(this, "该对话正在全屏使用中", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        sessionId = id
        items = engine.floatItems(id)
        messages.clear()
        messages.addAll(engine.floatGet(id)?.messages ?: emptyList())
        panelView?.findViewById<View>(R.id.float_history_view)?.visibility = View.GONE
        renderLog()
    }

    private fun renderLog() {
        adapter?.submit()
        adapter?.statusText = null
        titleText?.text = "小窗 · " + (LocalEngine.getInstance(appContext())?.floatGet(sessionId ?: "")?.title ?: "对话")
        scrollToBottom()
    }

    private fun showHistoryInPanel() {
        val panel = panelView ?: return
        val historyView = panel.findViewById<View>(R.id.float_history_view)
        val listView = panel.findViewById<LinearLayout>(R.id.float_history_list)
        listView.removeAllViews()
        val engine = LocalEngine.getInstance(appContext()) ?: return
        val list = engine.floatSessions()

        val back = TextView(this)
        back.text = "← 返回聊天"
        back.setTextColor(getColor(R.color.accent))
        back.textSize = 14f
        back.setPadding(14, 12, 14, 12)
        back.setOnClickListener {
            historyView.visibility = View.GONE
            renderLog()
        }
        listView.addView(back)

        if (list.isEmpty()) {
            val empty = TextView(this)
            empty.text = "（暂无历史对话）"
            empty.setTextColor(getColor(R.color.muted))
            empty.textSize = 13f
            empty.setPadding(14, 16, 14, 16)
            listView.addView(empty)
        } else {
            val grouped = list.groupBy { it.partition ?: "" }
            for ((name, sessions) in grouped.toSortedMap()) {
                val header = TextView(this)
                header.text = if (name.isEmpty()) "默认分区" else "◾ $name"
                header.setTextColor(getColor(R.color.accent))
                header.textSize = 12f
                header.setPadding(12, 10, 12, 4)
                listView.addView(header)
                for (s in sessions) {
                    val row = TextView(this)
                    val tokens = s.usage?.optLong("promptTokens")
                        ?.plus(s.usage?.optLong("completionTokens") ?: 0) ?: 0
                    row.text = "${s.title}\n$tokens tokens"
                    row.setTextColor(getColor(R.color.text))
                    row.textSize = 13f
                    row.setPadding(14, 10, 14, 10)
                    row.setBackgroundResource(R.drawable.bg_float_option)
                    val lp = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    lp.bottomMargin = 4
                    lp.topMargin = 4
                    row.layoutParams = lp
                    val locked = engine.runningSessionId()
                    if (s.id == locked) {
                        row.text = "${s.title}\n（全屏使用中）"
                    }
                    row.setOnClickListener { loadSession(s.id) }
                    row.setOnLongClickListener {
                        showRowActions(listView, row, s)
                        true
                    }
                    listView.addView(row)
                }
            }
        }
        titleText?.text = "历史对话"
        historyView.visibility = View.VISIBLE
    }

    private fun showRowActions(listView: LinearLayout, row: TextView, meta: com.webcode.app.api.SessionMeta) {
        val engine = LocalEngine.getInstance(appContext()) ?: return
        val actions = LinearLayout(this)
        actions.orientation = LinearLayout.HORIZONTAL
        actions.setPadding(6, 2, 6, 2)

        fun actionBtn(text: String, color: Int): TextView {
            val b = TextView(this)
            b.text = text
            b.setTextColor(color)
            b.textSize = 13f
            b.setPadding(16, 8, 16, 8)
            b.setBackgroundResource(R.drawable.bg_float_option)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = 8
            b.layoutParams = lp
            return b
        }

        val rename = actionBtn("重命名", getColor(R.color.accent))
        rename.setOnClickListener {
            val input = EditText(this)
            input.setText(meta.title)
            input.setTextColor(getColor(R.color.text))
            input.setBackgroundResource(R.drawable.bg_input)
            input.setPadding(10, 8, 10, 8)
            input.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            val idx = listView.indexOfChild(row)
            listView.removeViewAt(idx)
            listView.addView(input, idx)
            input.requestFocus()
            input.setOnEditorActionListener { _, _, _ ->
                val t = input.text.toString().trim()
                if (t.isNotEmpty()) {
                    engine.renameSession(meta.id, t)
                    loadSession(meta.id)
                    showHistoryInPanel()
                }
                true
            }
        }
        val del = actionBtn("删除", getColor(R.color.error))
        del.setOnClickListener {
            engine.deleteSession(meta.id)
            showHistoryInPanel()
        }
        val cancel = actionBtn("取消", getColor(R.color.muted))
        cancel.setOnClickListener { showHistoryInPanel() }

        actions.addView(rename)
        actions.addView(del)
        actions.addView(cancel)
        val idx = listView.indexOfChild(row)
        listView.removeViewAt(idx)
        listView.addView(actions, idx)
    }

    private fun save() {
        val engine = LocalEngine.getInstance(appContext()) ?: return
        val sid = sessionId ?: return
        try {
            engine.floatSave(sid, messages, items)
        } catch (e: Exception) {
        }
    }

    /* ============ 对话（与全屏一致的事件流） ============ */
    private fun sendToModel(text: String) {
        val engine = LocalEngine.getInstance(appContext()) ?: return
        if (!LocalEngine.isConfigured(this)) {
            android.widget.Toast.makeText(this, "请先在设置页填写 API Key", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        if (sessionId == null) newSession()
        running = true
        abort.set(false)

        val userMsg = SessionMessage(
            id = "f_" + System.currentTimeMillis(),
            role = "user",
            parts = mutableListOf(Part.Text(text)),
            createdAt = System.currentTimeMillis()
        )
        val aiMsg = SessionMessage(
            id = "f_" + (System.currentTimeMillis() + 1),
            role = "assistant",
            parts = mutableListOf(),
            createdAt = System.currentTimeMillis()
        )
        messages.add(userMsg)
        messages.add(aiMsg)
        items.put(DirectClient.messageItem("user", text))
        adapter?.submit()
        adapter?.statusText = "思考中…"
        adapter?.submit()
        scrollToBottom()

        Thread {
            try {
                val (key, baseUrl, model) = LocalEngine.loadConfig(this)
                val client = DirectClient(key, baseUrl, model)
                val buf = StringBuilder()

                val listener = object : DirectClient.Listener {
                    override fun onEvent(ev: DirectClient.ResponseEvent) {
                        when (ev.type) {
                            "response.output_text.delta" -> {
                                val t = ev.data.optString("delta")
                                if (t.isNotEmpty()) {
                                    handler.post {
                                        adapter?.appendDelta(aiMsg.id, t)
                                        scrollToBottom()
                                    }
                                }
                            }
                            "response.reasoning_text.delta" -> {
                                val t = ev.data.optString("delta")
                                if (t.isNotEmpty()) {
                                    handler.post {
                                        adapter?.appendThinkingDelta(aiMsg.id, t)
                                        scrollToBottom()
                                    }
                                }
                            }
                            "response.web_search_call.completed" -> {
                                handler.post {
                                    addToolCard(aiMsg.id, "web_search", "联网搜索", "联网搜索完成（服务端执行）")
                                }
                            }
                        }
                    }

                    override fun onError(message: String) {
                        DiagLog.log(this@FloatingChatService, "Float", "流错误: $message")
                        handler.post {
                            adapter?.statusText = "错误：$message"
                            adapter?.submit()
                        }
                    }
                }

                val result = client.create(
                    items, SMALL_SYSTEM_PROMPT, SMALL_TOOLS, listener, abort,
                    LocalEngine.reasoningSetting(this).takeIf { it != "auto" }
                )
                if (abort.get()) {
                    handler.post { adapter?.statusText = null; adapter?.submit() }
                    running = false
                    save()
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
                            "reasoning" -> items.put(item)
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
                            handler.post { addToolCard(aiMsg.id, name, "$name ${args.take(50)}", "") }
                            val resultStr = runTool(name, args)
                            items.put(DirectClient.functionCallOutputItem(callId, resultStr))
                            handler.post {
                                updateToolCard(aiMsg.id, callId, resultStr)
                            }
                        }
                        handler.post { adapter?.statusText = "工具完成，继续生成…"; adapter?.submit() }
                        continueConversation(aiMsg)
                    } else if (finalText.isNotEmpty()) {
                        appendFinal(aiMsg, finalText)
                    }
                } else {
                    handler.post { adapter?.statusText = "请求失败：${result.error ?: "未知错误"}"; adapter?.submit() }
                }
            } catch (e: Exception) {
                DiagLog.log(this, "Float", "对话异常: ${e.message}")
                handler.post { adapter?.statusText = "异常：${e.message ?: e.javaClass.simpleName}"; adapter?.submit() }
            } finally {
                running = false
                save()
                handler.post {
                    adapter?.statusText = null
                    adapter?.submit()
                    scrollToBottom()
                }
            }
        }.start()
    }

    private fun continueConversation(aiMsg: SessionMessage) {
        if (abort.get() || running) return
        Thread {
            try {
                val (key, baseUrl, model) = LocalEngine.loadConfig(this)
                val client = DirectClient(key, baseUrl, model)
                val buf = StringBuilder()
                val listener = object : DirectClient.Listener {
                    override fun onEvent(ev: DirectClient.ResponseEvent) {
                        if (ev.type == "response.output_text.delta") {
                            val t = ev.data.optString("delta")
                            if (t.isNotEmpty()) {
                                handler.post {
                                    adapter?.appendDelta(aiMsg.id, t)
                                    scrollToBottom()
                                }
                            }
                        }
                    }

                    override fun onError(message: String) {
                        handler.post { adapter?.statusText = "错误：$message"; adapter?.submit() }
                    }
                }
                val result = client.create(
                    items, SMALL_SYSTEM_PROMPT, SMALL_TOOLS, listener, abort,
                    LocalEngine.reasoningSetting(this).takeIf { it != "auto" }
                )
                if (result.completed && result.response != null) {
                    val text = result.response!!.optString("output_text")
                    if (text.isNotEmpty()) appendFinal(aiMsg, text)
                } else if (result.error != null) {
                    handler.post { adapter?.statusText = "请求失败：${result.error}"; adapter?.submit() }
                }
            } catch (e: Exception) {
                handler.post { adapter?.statusText = "异常：${e.message}"; adapter?.submit() }
            } finally {
                running = false
                save()
                handler.post {
                    adapter?.statusText = null
                    adapter?.submit()
                    scrollToBottom()
                }
            }
        }.start()
    }

    private fun addToolCard(msgId: String, tool: String, title: String, output: String) {
        val part = Part.Tool(
            id = "t_" + System.currentTimeMillis(),
            tool = tool,
            title = title,
            state = if (output.isEmpty()) "running" else "completed",
            input = JSONObject(),
            output = output.ifEmpty { null },
            approval = null,
            question = null
        )
        messages.find { it.id == msgId }?.parts?.add(part)
        adapter?.submit()
        scrollToBottom()
    }

    private fun updateToolCard(msgId: String, callId: String, output: String) {
        val m = messages.find { it.id == msgId } ?: return
        m.parts.filterIsInstance<Part.Tool>().lastOrNull()?.let { p ->
            p.state = "completed"
            p.output = output
        }
        adapter?.submit()
        scrollToBottom()
    }

    private fun appendFinal(aiMsg: SessionMessage, text: String) {
        handler.post {
            val last = aiMsg.parts.lastOrNull()
            if (last is Part.Text) {
                last.text += text
            } else {
                aiMsg.parts.add(Part.Text(text))
            }
            adapter?.submit()
            scrollToBottom()
        }
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
                else -> "未知工具: $name"
            }
        } catch (e: Exception) {
            "执行失败: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun scrollToBottom() {
        chatList?.post {
            val a = adapter ?: return@post
            if (a.itemCount > 0) chatList?.scrollToPosition(a.itemCount - 1)
        }
    }

    private fun appContext(): Context = applicationContext

    private fun Int.dp(): Int =
        (this * resources.displayMetrics.density).toInt()

    override fun onApprove(part: Part.Tool) {
        android.widget.Toast.makeText(this, "审批请在全屏界面操作", android.widget.Toast.LENGTH_SHORT).show()
    }

    override fun onReject(part: Part.Tool) {
        android.widget.Toast.makeText(this, "审批请在全屏界面操作", android.widget.Toast.LENGTH_SHORT).show()
    }

    override fun onAnswer(part: Part.Tool, answer: String) {
        android.widget.Toast.makeText(this, "提问请在全屏界面操作", android.widget.Toast.LENGTH_SHORT).show()
    }

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
