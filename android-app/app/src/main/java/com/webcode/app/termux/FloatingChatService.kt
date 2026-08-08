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
import android.widget.FrameLayout
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
import com.webcode.app.local.LocalStore
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
    private var floatSendBtn: android.widget.ImageButton? = null
    private var panelContent: View? = null
    @Volatile
    private var runningProc: Process? = null

    /* ============ AI 状态 → 悬浮球状态色 ============ */
    private val AI_IDLE = 0
    private val AI_THINKING = 1
    private val AI_OUTPUT = 2
    private val AI_BUSY = 3

    @Volatile
    private var aiState = AI_IDLE
    private var blinkRunnable: Runnable? = null
    private var blinkGreen = false

    private fun setAiState(state: Int) {
        aiState = state
        handler.post { updateBubbleColor() }
    }

    private fun updateBubbleColor() {
        val root = bubbleView?.findViewById<View>(R.id.bubble_root) ?: return
        val bg = root.background
        when (aiState) {
            AI_THINKING -> bg.setTint(0xFFFF9800.toInt()) // 思考：橙色
            AI_OUTPUT -> bg.setTint(0xFF2196F3.toInt())   // 输出：蓝色
            AI_BUSY -> startBlinkGreen(root, bg)          // 工具执行：绿色闪烁
            else -> bg.setTint(0xFF7C6CFF.toInt())        // 空闲：默认紫
        }
    }

    private fun startBlinkGreen(root: View, bg: android.graphics.drawable.Drawable) {
        stopBlink()
        blinkGreen = false
        blinkRunnable = object : Runnable {
            override fun run() {
                if (aiState != AI_BUSY) {
                    stopBlink()
                    updateBubbleColor()
                    return
                }
                blinkGreen = !blinkGreen
                bg.setTint(if (blinkGreen) 0xFF4CAF50.toInt() else 0xFF2E7D32.toInt())
                handler.postDelayed(this, 500)
            }
        }
        handler.postDelayed(blinkRunnable!!, 0)
    }

    private fun stopBlink() {
        blinkRunnable?.let { handler.removeCallbacks(it) }
        blinkRunnable = null
    }

    /** 中断：停止对话循环并立即终止正在执行的命令 */
    private fun interrupt() {
        abort.set(true)
        running = false
        try {
            runningProc?.destroyForcibly()
        } catch (e: Exception) {
        }
        updateSendButton()
        adapter?.statusText = "已停止"
        adapter?.submit()
    }

    /** 发送/中断按钮状态切换 */
    private fun updateSendButton() {
        try {
            floatSendBtn?.setImageResource(
                if (running) R.drawable.ic_stop else R.drawable.ic_send
            )
        } catch (e: Exception) {
        }
    }

    private var lastX = 0f
    private var lastY = 0f
    private var moved = false

    private var sessionId: String? = null
    private var items = JSONArray()
    private val messages = mutableListOf<SessionMessage>()
    private val abort = AtomicBoolean(false)
    @Volatile
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // 前台服务 + 通知：保活（否则后台一段时间悬浮窗服务会被系统杀掉）
        try {
            startForeground(FLOAT_NOTIF_ID, buildKeepAliveNotification())
        } catch (e: Exception) {
        }
        // Termux 同款三件套保活：悬浮窗 + 前台通知 + 电池优化豁免
        try {
            BgService.requestBattery(this)
        } catch (e: Exception) {
        }
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        showBubble()
        // 小窗自己的会话记忆优先（重启后恢复小窗上次使用的会话），否则继承全屏最近会话；
        // 都无效时不自动创建（等用户发消息时才建），避免服务频繁重启产生大量空会话
        val engine = LocalEngine.getInstance(appContext())
        val floatLast = getSharedPreferences("float", MODE_PRIVATE).getString("last_session", null)
        if (floatLast != null && engine?.floatGet(floatLast) != null) {
            loadSession(floatLast)
            return
        }
        val last = LocalEngine.lastSession(this)
        if (last != null && engine?.floatGet(last) != null &&
            last != engine.runningSessionId()
        ) {
            loadSession(last)
        }
    }

    override fun onDestroy() {
        abort.set(true)
        stopBlink()
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
            // 长按悬浮球 = 中断 AI（面板收起时也能停止输出）
            view.setOnLongClickListener {
                if (running) {
                    interrupt()
                    android.widget.Toast.makeText(this, "已停止 AI 输出", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    togglePanel()
                }
                true
            }
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
                // 默认不抢焦点（否则聊天时小窗会抢走主界面输入焦点）；
                // 输入框聚焦时由 onFocusChange 临时移除该 flag
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
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

            // 内容面板跟随窗口尺寸自适应（窗口缩放时布局自动重排）
            val content = view.findViewById<View>(R.id.float_panel_content)
            panelContent = content

            chatList = view.findViewById(R.id.float_chat_list)
            titleText = view.findViewById(R.id.float_title)
            inputBox = view.findViewById(R.id.float_input)
            // 输入框聚焦时才允许窗口获得焦点（弹键盘），失焦恢复不抢焦点。
            // 注意：NOT_FOCUSABLE 窗口内 EditText 无法直接聚焦，按下时先切换窗口 flag，抬起后聚焦+弹键盘
            inputBox?.setOnTouchListener { v, ev ->
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> {
                        try {
                            params.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            wm?.updateViewLayout(view, params)
                        } catch (e: Exception) {
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        v.requestFocus()
                        v.post {
                            v.requestFocus()
                            try {
                                val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                                        as android.view.inputmethod.InputMethodManager
                                imm.showSoftInput(v, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                            } catch (e: Exception) {
                            }
                        }
                        true
                    }
                    else -> true
                }
            }
            inputBox?.setOnFocusChangeListener { _, hasFocus ->
                try {
                    params.flags = if (hasFocus) {
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    } else {
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    }
                    wm?.updateViewLayout(view, params)
                } catch (e: Exception) {
                }
            }
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
            // 思考设置：单选对话框，点击即保存（持久生效，关闭面板不丢）
            val thinkingView = view.findViewById<TextView>(R.id.float_opt_thinking)
            fun refreshThinkingLabel() {
                val cur = LocalEngine.reasoningSetting(this)
                val on = if (cur == "none") "关" else "开"
                val effort = when (cur) {
                    "none" -> "关闭"
                    "low" -> "低"
                    "medium" -> "中"
                    "high" -> "高"
                    "max" -> "最高"
                    else -> "自动"
                }
                thinkingView.text = "思考设置：$on · $effort"
            }
            refreshThinkingLabel()
            thinkingView.setOnClickListener {
                val options = listOf("思考：关闭", "思考：开启 · 自动", "思考：开启 · 低", "思考：开启 · 中", "思考：开启 · 高", "思考：开启 · 最高")
                val values = listOf("none", "auto", "low", "medium", "high", "max")
                val cur = LocalEngine.reasoningSetting(this)
                val checked = values.indexOf(cur).coerceAtLeast(0)
                // Service 上下文必须用 overlay 窗口类型，否则 Dialog 弹不出来
                val dlg = android.app.AlertDialog.Builder(this)
                    .setTitle("思考设置")
                    .setSingleChoiceItems(options.toTypedArray(), checked) { d, which ->
                        LocalEngine.setReasoning(this, values[which])
                        refreshThinkingLabel()
                        d.dismiss()
                    }
                    .setNegativeButton("取消", null)
                    .create()
                try {
                    dlg.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                } catch (e: Exception) {
                }
                dlg.show()
            }
            menuOverlay.setOnClickListener { menuOverlay.visibility = View.GONE }

            val floatSendBtn = view.findViewById<android.widget.ImageButton>(R.id.float_send)
            this.floatSendBtn = floatSendBtn
            view.findViewById<View>(R.id.float_send).setOnClickListener {
                if (running) {
                    // 运行中点击 = 中断（立即停止循环 + 终止命令）
                    interrupt()
                } else {
                    val text = inputBox?.text.toString().trim()
                    if (text.isNotEmpty()) {
                        inputBox?.setText("")
                        sendToModel(text)
                    }
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
            // 重开面板时按当前运行状态刷新按钮图标（否则 AI 输出中会显示"发送"，误导且无法停止）
            updateSendButton()
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
        getSharedPreferences("float", MODE_PRIVATE).edit().putString("last_session", id).apply()
        items = engine.floatItems(id)
        messages.clear()
        messages.addAll(engine.floatGet(id)?.messages ?: emptyList())
        panelView?.findViewById<View>(R.id.float_history_view)?.visibility = View.GONE
        renderLog()
    }

    private fun renderLog() {
        adapter?.submit()
        adapter?.statusText = null
        try {
            val engine = LocalEngine.getInstance(appContext())
            val meta = engine?.floatGet(sessionId ?: "")
            val usage = meta?.usage
            val tokens = (usage?.optLong("promptTokens") ?: 0L) + (usage?.optLong("completionTokens") ?: 0L)
            titleText?.text = "小窗 · ${meta?.title ?: "对话"}" +
                if (tokens > 0) " ($tokens tokens)" else ""
        } catch (e: Exception) {
            titleText?.text = "小窗 · " + (LocalEngine.getInstance(appContext())?.floatGet(sessionId ?: "")?.title ?: "对话")
        }
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
        // 通过 handler 入队保存：保证在排队的流式渲染之后执行，避免尾部内容丢失
        handler.post {
            val engine = LocalEngine.getInstance(appContext()) ?: return@post
            val sid = sessionId ?: return@post
            try {
                engine.floatSave(sid, messages, items)
                // 通知全屏界面刷新会话列表（overlay 不会触发 MainActivity.onResume）
                try {
                    sendBroadcast(android.content.Intent("webcode.sessions_changed").setPackage(packageName))
                } catch (e: Exception) {
                }
            } catch (e: Exception) {
                DiagLog.log(this, "Float", "保存失败: ${e.message}")
            }
        }
    }

    /** 首次消息时设置会话标题（与全屏一致），并立即落盘 */
    private fun ensureRecorded(text: String) {
        val engine = LocalEngine.getInstance(appContext()) ?: return
        val sid = sessionId ?: return
        val meta = engine.floatSessions().find { it.id == sid }
        if (meta != null && (meta.title.isEmpty() || meta.title == "小窗对话")) {
            engine.renameSession(sid, text.take(24))
        }
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
        if (running) {
            // 防并发：AI 输出中不允许再发消息（点击中断按钮请走 interrupt）
            android.widget.Toast.makeText(this, "AI 正在输出，请先停止", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        if (sessionId == null) newSession()
        running = true
        abort.set(false)
        updateSendButton()
        setAiState(AI_THINKING)

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
        // 立即落盘（标题 + 用户消息），防止中途退出丢失
        ensureRecorded(text)

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
                                    setAiState(AI_OUTPUT)
                                    handler.post {
                                        adapter?.appendDelta(aiMsg.id, t)
                                        scrollToBottom()
                                    }
                                }
                            }
                            "response.reasoning_text.delta" -> {
                                val t = ev.data.optString("delta")
                                if (t.isNotEmpty()) {
                                    setAiState(AI_THINKING)
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
                    setAiState(AI_IDLE)
                    updateSendButton()
                    save()
                    return@Thread
                }

                if (result.completed && result.response != null) {
                    val resp = result.response!!
                    saveUsage(resp)
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
                        setAiState(AI_BUSY)
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
                setAiState(AI_IDLE)
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
        // 完整多轮工具循环：每次 create 后解析 output，
        // 有 function_call 就执行并继续下一轮，直到模型给出最终文本或 abort。
        // 注意：此方法在主循环线程内串行调用（running 仍为 true），只检查 abort。
        if (abort.get()) return
        Thread {
            try {
                while (!abort.get()) {
                    val (key, baseUrl, model) = LocalEngine.loadConfig(this)
                    val client = DirectClient(key, baseUrl, model)
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
                    if (abort.get()) break
                    if (!result.completed || result.response == null) {
                        if (result.error != null) {
                            handler.post { adapter?.statusText = "请求失败：${result.error}"; adapter?.submit() }
                        }
                        break
                    }

                    saveUsage(result.response!!)
                    val resp = result.response!!
                    val output = resp.optJSONArray("output")
                    val calls = mutableListOf<Pair<JSONObject, String>>()
                    var finalText = StringBuilder()
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
                                        finalText.append(c.optString("text"))
                                    }
                                }
                            }
                        }
                    }

                    if (calls.isNotEmpty()) {
                        var hasTool = false
                        for ((call, callId) in calls) {
                            if (abort.get()) break
                            val name = call.optString("name")
                            val args = call.optString("arguments")
                            handler.post { addToolCard(aiMsg.id, name, "$name ${args.take(50)}", "") }
                            val resultStr = runTool(name, args)
                            items.put(DirectClient.functionCallOutputItem(callId, resultStr))
                            handler.post { updateToolCard(aiMsg.id, callId, resultStr) }
                            hasTool = true
                        }
                        if (!hasTool) break
                        handler.post { adapter?.statusText = "工具完成，继续生成…"; adapter?.submit() }
                        continue // 关键：继续下一轮工具循环
                    } else if (finalText.isNotEmpty()) {
                        appendFinal(aiMsg, finalText.toString())
                    }
                    break
                }
            } catch (e: Exception) {
                handler.post { adapter?.statusText = "异常：${e.message}"; adapter?.submit() }
            } finally {
                running = false
                setAiState(AI_IDLE)
                updateSendButton()
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
                        try {
                            LocalAgentManager.runCommand(
                                this, command, null,
                                (args.optInt("timeout", 30).coerceAtMost(60)) * 1000L
                            ) { p -> runningProc = p }
                                .trim().ifEmpty { "（无输出）" }
                        } finally {
                            runningProc = null
                        }
                    }
                }
                "tty_read" -> {
                    if (!com.webcode.app.local.LocalEngine.ttyAccess(this)) {
                        "未授权：请在设置中开启「允许 AI 读取/注入终端」"
                    } else if (com.webcode.app.ui.TerminalActivity.current() != null) {
                        com.webcode.app.ui.TerminalActivity.readTty(maxLines = args.optInt("lines", 200))
                            ?: "读取终端失败"
                    } else if (com.webcode.app.termux.FloatingTerminalService.current() != null) {
                        com.webcode.app.termux.FloatingTerminalService.readTty(maxLines = args.optInt("lines", 200))
                            ?: "读取终端失败"
                    } else {
                        "当前未打开终端页面（全屏终端或终端小窗），无法读取 tty"
                    }
                }
                "tty_read_raw" -> {
                    if (!com.webcode.app.local.LocalEngine.ttyAccess(this)) {
                        "未授权：请在设置中开启「允许 AI 读取/注入终端」"
                    } else if (com.webcode.app.ui.TerminalActivity.current() != null) {
                        com.webcode.app.ui.TerminalActivity.readTtyRaw()
                            ?: "读取终端失败"
                    } else if (com.webcode.app.termux.FloatingTerminalService.current() != null) {
                        com.webcode.app.termux.FloatingTerminalService.readTtyRaw()
                            ?: "读取终端失败"
                    } else {
                        "当前未打开终端页面（全屏终端或终端小窗），无法读取 tty"
                    }
                }
                "tty_send" -> {
                    if (!com.webcode.app.local.LocalEngine.ttyAccess(this)) {
                        "未授权：请在设置中开启「允许 AI 读取/注入终端」"
                    } else {
                        val cmd = args.optString("command", "")
                        if (cmd.isEmpty()) "命令为空"
                        else if (com.webcode.app.ui.TerminalActivity.current() != null) {
                            if (com.webcode.app.ui.TerminalActivity.writeTty(cmd)) "已注入终端并执行：$cmd"
                            else "注入终端失败"
                        } else if (com.webcode.app.termux.FloatingTerminalService.current() != null) {
                            if (com.webcode.app.termux.FloatingTerminalService.writeTty(cmd)) "已注入终端并执行：$cmd"
                            else "注入终端失败"
                        } else {
                            "当前未打开终端页面（全屏终端或终端小窗），无法注入 tty"
                        }
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

    /** 累计保存小窗会话的 token 统计（与全屏 usage 同存储） */
    private fun saveUsage(resp: JSONObject) {
        try {
            val usage = resp.optJSONObject("usage") ?: return
            val sid = sessionId ?: return
            val pt = usage.optLong("input_tokens")
            val ct = usage.optLong("output_tokens")
            if (pt <= 0 && ct <= 0) return
            val engine = LocalEngine.getInstance(appContext()) ?: return
            val old = engine.floatGet(sid)?.usage
            val oldP = old?.optLong("promptTokens") ?: 0L
            val oldC = old?.optLong("completionTokens") ?: 0L
            LocalStore(this).setUsage(sid, oldP + pt, oldC + ct)
        } catch (e: Exception) {
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

    private fun buildKeepAliveNotification(): android.app.Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = android.app.NotificationChannel(
                "webcode_float", "小窗保活", android.app.NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(android.app.NotificationManager::class.java).createNotificationChannel(ch)
        }
        val open = android.app.PendingIntent.getActivity(
            this, 0,
            Intent(this, com.webcode.app.ui.LocalSetupActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE
        )
        return androidx.core.app.NotificationCompat.Builder(this, "webcode_float")
            .setSmallIcon(R.drawable.ic_robot)
            .setContentTitle("WebCode 小窗运行中")
            .setContentText("点击管理 · 悬浮球可聊天")
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val FLOAT_NOTIF_ID = 3457

        private val SMALL_SYSTEM_PROMPT = """
            你是运行在用户 Android 手机小窗里的本地 AI 助手 "WebCode Local"。
            可用工具：web_search（联网搜索）、run_command（bash，危险命令会被拒绝）、device_info（设备信息）、open_url（打开网页）、tty_read/tty_read_raw/tty_send（读取/写入终端页，需用户授权）。
            工作方式：可以连续调用多个工具直到任务完成；工具出错时分析原因并继续修复（如 dpkg 被中断先运行 dpkg --configure -a），不要调用一次就草率结束；任务真正完成后再给出最终回答。
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
                "tty_read", "读取当前终端页（tty）最近输出；返回提示「输出过长」时改用 tty_read_raw 读原文",
                JSONObject()
                    .put("lines", JSONObject().put("type", "integer")),
                emptyList()
            ),
            DirectClient.functionTool(
                "tty_read_raw", "读取当前终端页（tty）完整输出原文（无长度上限）",
                JSONObject(),
                emptyList()
            ),
            DirectClient.functionTool(
                "tty_send", "向当前终端页（tty）注入命令并回车执行",
                JSONObject()
                    .put("command", JSONObject().put("type", "string")),
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
