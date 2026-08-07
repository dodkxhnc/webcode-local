package com.webcode.app.termux

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import com.webcode.app.R

/**
 * 终端小窗（开发中）：悬浮球 → 展开与全屏终端一致的 Ubuntu bash 终端。
 * 独立会话（proot + PTY），与全屏终端共享 rootfs。
 */
class FloatingTerminalService : Service(), TerminalSessionClient {

    companion object {
        private const val NOTIF_ID = 3458

        @Volatile
        private var instance: FloatingTerminalService? = null

        fun current(): FloatingTerminalService? = instance

        /** AI 工具入口：读取当前终端 tty 最近输出（终端小窗也算打开 tty） */
        fun readTty(maxLines: Int = 200): String? {
            val act = instance ?: return null
            return act.readTtyText(maxLines)
        }

        /** AI 工具入口：读取终端完整输出原文 */
        fun readTtyRaw(): String? {
            val act = instance ?: return null
            return act.readTtyRaw()
        }

        /** AI 工具入口：向终端 tty 注入命令并回车执行 */
        fun writeTty(command: String): Boolean {
            val act = instance ?: return false
            return act.writeTtyText(command)
        }

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, FloatingTerminalService::class.java))
            } catch (e: Exception) {
                try {
                    context.startService(Intent(context, FloatingTerminalService::class.java))
                } catch (e2: Exception) {
                }
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, FloatingTerminalService::class.java))
            } catch (e: Exception) {
            }
        }
    }

    private var wm: WindowManager? = null
    private var bubbleView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var panelView: View? = null
    private var panelParams: WindowManager.LayoutParams? = null

    private var terminalView: TerminalView? = null
    private var session: TerminalSession? = null
    private var process: Process? = null
    private var ptyFd = -1
    private var panelInput: EditText? = null

    private var lastX = 0f
    private var lastY = 0f
    private var moved = false

    private var ctrlMode = false
    private var altMode = false

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        try {
            startForeground(NOTIF_ID, buildKeepAliveNotification())
        } catch (e: Exception) {
        }
        try {
            BgService.requestBattery(this)
        } catch (e: Exception) {
        }
        if (!android.provider.Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        showBubble()
        createTermSession()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        try {
            session?.finishIfRunning()
        } catch (e: Exception) {
        }
        try {
            if (ptyFd > 0) TerminalPty.ptyClose(ptyFd)
        } catch (e: Exception) {
        }
        try {
            bubbleView?.let { wm?.removeView(it) }
        } catch (e: Exception) {
        }
        try {
            panelView?.let { wm?.removeView(it) }
        } catch (e: Exception) {
        }
        super.onDestroy()
    }

    private fun overlayType(): Int =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /* ============ 悬浮球 ============ */
    private fun showBubble() {
        try {
            wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val w = dp(36)
            val view = LayoutInflater.from(this).inflate(R.layout.float_terminal_bubble, null)
            val params = WindowManager.LayoutParams(
                w, w,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP or Gravity.START
            params.x = 24
            params.y = 24 * 10

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
                                wm?.updateViewLayout(v, params)
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

            wm?.addView(view, params)
            bubbleView = view
            bubbleParams = params
        } catch (e: Exception) {
            DiagLog.log(this, "FTerm", "悬浮球失败: ${e.message ?: e.javaClass.simpleName}")
            stopSelf()
        }
    }

    /** 焦点协调：输入框或 tty 任一获得焦点 → 窗口可聚焦；全部失焦 → 恢复不抢焦点 */
    private fun updateFocusFlags() {
        try {
            val focused = (terminalView?.hasFocus() == true) || (panelInput?.hasFocus() == true)
            val lp = panelParams ?: return
            val newFlags = if (focused) {
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            } else {
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            }
            if (lp.flags != newFlags) {
                lp.flags = newFlags
                panelView?.let { wm?.updateViewLayout(it, lp) }
            }
        } catch (e: Exception) {
        }
    }

    private fun requestFocusOn(view: View?) {
        view?.let { v ->
            updateFocusFlags()
            v.requestFocus()
            v.post {
                updateFocusFlags()
                v.requestFocus()
                try {
                    val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                            as android.view.inputmethod.InputMethodManager
                    imm.showSoftInput(v, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                } catch (e: Exception) {
                }
            }
        }
    }

    private fun togglePanel() {
        if (panelView != null) removePanel() else showPanel()
    }

    private fun showPanel() {
        try {
            if (wm == null || bubbleView == null) return
            val w = dp(300)
            val h = dp(420)
            val view = LayoutInflater.from(this).inflate(R.layout.float_terminal_panel, null)
            val params = WindowManager.LayoutParams(
                w, h,
                overlayType(),
                // 默认不抢焦点（否则干扰主界面输入），输入框聚焦时临时恢复
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )
            params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            params.gravity = Gravity.TOP or Gravity.START
            val screenW = resources.displayMetrics.widthPixels
            val screenH = resources.displayMetrics.heightPixels
            val bx = bubbleParams?.x ?: 24
            val by = bubbleParams?.y ?: 24
            var px = bx - 100
            if (px < 0) px = bx + 60
            params.x = px.coerceIn(0, (screenW - w - 8).coerceAtLeast(0))
            params.y = by.coerceIn(0, (screenH - h - 8).coerceAtLeast(0))

            // 挂 TerminalView
            val frame = view.findViewById<android.widget.FrameLayout>(R.id.term_panel_frame)
            val tv = terminalView
            if (tv != null) {
                if (tv.parent != null) {
                    (tv.parent as? android.view.ViewGroup)?.removeView(tv)
                }
                frame.addView(
                    tv,
                    android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )
            }

            // 扩展键
            view.findViewById<View>(R.id.ft_key_esc).setOnClickListener { sendRaw("\u001b") }
            view.findViewById<View>(R.id.ft_key_tab).setOnClickListener { sendRaw("\t") }
            view.findViewById<View>(R.id.ft_key_up).setOnClickListener { sendRaw("\u001b[A") }
            view.findViewById<View>(R.id.ft_key_down).setOnClickListener { sendRaw("\u001b[B") }
            view.findViewById<View>(R.id.ft_key_left).setOnClickListener { sendRaw("\u001b[D") }
            view.findViewById<View>(R.id.ft_key_right).setOnClickListener { sendRaw("\u001b[C") }
            view.findViewById<View>(R.id.ft_key_ctrl).setOnClickListener {
                ctrlMode = !ctrlMode
                altMode = false
                (it as TextView).setTextColor(if (ctrlMode) Color.parseColor("#7c6cff") else Color.parseColor("#E8E8E8"))
                view.findViewById<TextView>(R.id.ft_key_alt).setTextColor(Color.parseColor("#E8E8E8"))
            }
            view.findViewById<View>(R.id.ft_key_alt).setOnClickListener {
                altMode = !altMode
                ctrlMode = false
                (it as TextView).setTextColor(if (altMode) Color.parseColor("#7c6cff") else Color.parseColor("#E8E8E8"))
                view.findViewById<TextView>(R.id.ft_key_ctrl).setTextColor(Color.parseColor("#E8E8E8"))
            }

            // 输入
            val input = view.findViewById<EditText>(R.id.ft_input)
            panelInput = input
            // NOT_FOCUSABLE 窗口内 EditText 无法直接获得焦点：按下时先切窗口 flag，抬起后聚焦 + 弹键盘（post 重试）
            input.setOnTouchListener { v, ev ->
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> {
                        updateFocusFlags()
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        requestFocusOn(v)
                        true
                    }
                    else -> true
                }
            }
            input.setOnFocusChangeListener { _, _ -> updateFocusFlags() }
            view.findViewById<View>(R.id.ft_send).setOnClickListener { sendCommand(input) }
            input.setOnEditorActionListener { _, _, _ ->
                sendCommand(input)
                true
            }

            // 缩放手柄
            setupResizeHandle(view)

            // 拖动
            view.findViewById<View>(R.id.term_panel_header).setOnTouchListener { v, event ->
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
                            wm?.updateViewLayout(view, params)
                        } catch (e: Exception) {
                        }
                        true
                    }
                    else -> false
                }
            }
            view.findViewById<View>(R.id.term_panel_close).setOnClickListener { removePanel() }

            wm?.addView(view, params)
            panelView = view
            panelParams = params
        } catch (e: Exception) {
            DiagLog.log(this, "FTerm", "面板失败: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun removePanel() {
        try {
            panelView?.let { wm?.removeView(it) }
        } catch (e: Exception) {
        }
        panelView = null
        panelParams = null
    }

    /** 右下角 ◢ 缩放手柄：调整面板窗口大小（防抖，避免拖动时频繁 SIGWINCH 重排终端） */
    private fun setupResizeHandle(panel: View) {
        val handle = panel.findViewById<View>(R.id.ft_resize)
        var startX = 0f
        var startY = 0f
        var startW = 0
        var startH = 0
        var lastUpdate = 0L
        handle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    val lp = panelParams
                    if (lp != null) {
                        startW = lp.width
                        startH = lp.height
                    }
                    lastUpdate = System.currentTimeMillis()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val lp = panelParams ?: return@setOnTouchListener true
                    val dx = (event.rawX - startX).toInt()
                    val dy = (event.rawY - startY).toInt()
                    val nw = (startW + dx).coerceIn(dp(240), dp(560))
                    val nh = (startH + dy).coerceIn(dp(300), dp(900))
                    if (nw == lp.width && nh == lp.height) return@setOnTouchListener true
                    val now = System.currentTimeMillis()
                    if (now - lastUpdate < 60) return@setOnTouchListener true // 防抖合并
                    lastUpdate = now
                    lp.width = nw
                    lp.height = nh
                    try {
                        panelView?.let { wm?.updateViewLayout(it, lp) }
                    } catch (e: Exception) {
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    // 最终尺寸校准一次（合并最后一次 move）
                    val lp = panelParams ?: return@setOnTouchListener true
                    val dx = (event.rawX - startX).toInt()
                    val dy = (event.rawY - startY).toInt()
                    lp.width = (startW + dx).coerceIn(dp(240), dp(560))
                    lp.height = (startH + dy).coerceIn(dp(300), dp(900))
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

    /* ============ 终端会话（与全屏一致） ============ */
    private fun createTermSession() {
        try {
            val nameBuf = ByteArray(64)
            val masterFd = TerminalPty.ptyOpen(nameBuf)
            if (masterFd < 0) return
            val slavePath = String(nameBuf, 0, nameBuf.indexOf(0).takeIf { it > 0 } ?: nameBuf.size, Charsets.UTF_8)

            val root = java.io.File(TermuxRuntime.rootfsDir)
            if (!java.io.File(root, "bin/bash").exists()) {
                TerminalPty.ptyClose(masterFd)
                return
            }
            val workspace = java.io.File(filesDir, "workspace")
            workspace.mkdirs()
            val rootHome = java.io.File(root, "root")
            rootHome.mkdirs()
            val hostTmp = java.io.File(TermuxRuntime.prefixDir, "tmp")
            hostTmp.mkdirs()
            TermuxRuntime.ensureDns()

            val hostDpkg = java.io.File(TermuxRuntime.ensureHostDpkgDir(this))

            val args = mutableListOf(
                TermuxRuntime.binDir + "/proot",
                "--link2symlink",
                "-0", "-r", root.absolutePath
            )
            args.add("-b"); args.add("${workspace.absolutePath}:/workspace")
            args.add("-b"); args.add("${rootHome.absolutePath}:/root")
            args.add("-b"); args.add("${hostTmp.absolutePath}:/tmp")
            args.add("-b"); args.add("${hostDpkg.absolutePath}:/var/lib/dpkg")
            args.add("-b"); args.add("/proc:/proc")
            args.add("-b"); args.add("/dev:/dev")
            args.add("-b"); args.add("/sys:/sys")
            args.add("-w"); args.add("/root")
            val rc = java.io.File(rootHome, ".webcode_rc")
            if (!rc.exists()) {
                try {
                    rc.writeText(
                        "set +m\n" +
                        "PS1='\\[\\e[1;32m\\]webcode@ubuntu\\[\\e[0m\\]:\\[\\e[1;34m\\]\\w\\[\\e[0m\\]# '\n" +
                        "[ -f ~/.bashrc ] && . ~/.bashrc 2>/dev/null; PS1='\\[\\e[1;32m\\]webcode@ubuntu\\[\\e[0m\\]:\\[\\e[1;34m\\]\\w\\[\\e[0m\\]# '\n" +
                        "command -v python >/dev/null 2>&1 || alias python=python3 2>/dev/null\n"
                    )
                } catch (e: Exception) {
                }
            }
            val inner = "exec /usr/bin/setsid /bin/bash --rcfile /root/.webcode_rc -i <$slavePath >$slavePath 2>&1"
            args.add("/bin/bash"); args.add("-c"); args.add(inner)

            val env = HashMap<String, String>()
            env["PATH"] = "/usr/bin:/bin:/usr/sbin:/sbin"
            env["HOME"] = "/root"
            env["TERM"] = "xterm-256color"
            env["LANG"] = "en_US.UTF-8"
            env["PROOT_TMP_DIR"] = hostTmp.absolutePath
            env["TMPDIR"] = "/tmp"
            env["PROOT_LOADER"] = TermuxRuntime.prefixDir + "/libexec/proot/loader"
            env["PROOT_LOADER_32"] = TermuxRuntime.prefixDir + "/libexec/proot/loader32"
            env["LD_LIBRARY_PATH"] = TermuxRuntime.prefixDir + "/lib"

            val pb = ProcessBuilder(args)
            pb.environment().putAll(env)
            pb.redirectErrorStream(true)
            val p = pb.start()

            val s = TerminalSession(masterFd, p, null, this)
            val tv = TerminalView(this, null)
            tv.setTerminalViewClient(termViewClient)
            tv.setFocusable(true)
            tv.setFocusableInTouchMode(true)
            tv.setTextSize(13)
            tv.setOnFocusChangeListener { _, _ -> updateFocusFlags() }
            tv.attachSession(s)

            this.terminalView = tv
            this.session = s
            this.process = p
            this.ptyFd = masterFd

            // 面板已开则挂载
            panelView?.findViewById<android.widget.FrameLayout>(R.id.term_panel_frame)?.let { frame ->
                frame.addView(
                    tv,
                    android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )
            }
        } catch (e: Exception) {
            DiagLog.log(this, "FTerm", "会话创建失败: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private val termViewClient = object : TerminalViewClient {
        override fun onSingleTapUp(e: MotionEvent) {
            // 点击 tty：切换窗口可聚焦后请求焦点 + 弹键盘（NOT_FOCUSABLE 窗口内 requestFocus 无效）
            requestFocusOn(terminalView)
        }

        override fun shouldBackButtonBeMappedToEscape(): Boolean = false
        override fun shouldEnforceCharBasedInput(): Boolean = false
        override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
        override fun isTerminalViewSelected(): Boolean = true
        override fun copyModeChanged(copyMode: Boolean) {}
        override fun onKeyDown(keyCode: Int, e: android.view.KeyEvent, session: TerminalSession): Boolean = false
        override fun onKeyUp(keyCode: Int, e: android.view.KeyEvent): Boolean = false
        override fun onLongPress(event: MotionEvent): Boolean = false
        override fun readControlKey(): Boolean = ctrlMode
        override fun readAltKey(): Boolean = altMode
        override fun readShiftKey(): Boolean = false
        override fun readFnKey(): Boolean = false
        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false
        override fun onModifierKeysConsumed() {
            ctrlMode = false
            altMode = false
            panelView?.findViewById<TextView>(R.id.ft_key_ctrl)?.setTextColor(Color.parseColor("#E8E8E8"))
            panelView?.findViewById<TextView>(R.id.ft_key_alt)?.setTextColor(Color.parseColor("#E8E8E8"))
        }
        override fun onEmulatorSet() {}
        override fun onScale(scale: Float): Float {
            val tv = terminalView ?: return scale
            var size = 13f * scale
            size = size.coerceIn(8f, 48f)
            tv.setTextSize(size.toInt())
            return 1f
        }
        override fun logError(tag: String, message: String) {}
        override fun logWarn(tag: String, message: String) {}
        override fun logInfo(tag: String, message: String) {}
        override fun logDebug(tag: String, message: String) {}
        override fun logVerbose(tag: String, message: String) {}
        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {}
        override fun logStackTrace(tag: String, e: Exception) {}
    }

    /* ============ AI tty 读写（与全屏终端一致，需设置中开启权限） ============ */
    fun readTtyText(maxLines: Int): String? {
        val s = session ?: return null
        val emu = s.emulator ?: return null
        val text = emu.getScreen().getTranscriptText()
        if (text.isBlank()) return "(终端无内容)"
        val lines = text.lines()
        val shown = if (lines.size > maxLines) lines.takeLast(maxLines) else lines
        val body = shown.joinToString("\n")
        return if (body.length > 30000) {
            "终端输出过长（最近 ${shown.size} 行共 ${body.length} 字符，已超出单次传输上限）。" +
                "如需完整原文，请调用 tty_read_raw 工具读取全部内容。\n" +
                "（以下为最近 ${shown.size} 行的开头部分）\n" + body.take(3000)
        } else {
            "终端最近 ${shown.size}/${lines.size} 行：\n" + body
        }
    }

    fun readTtyRaw(): String? {
        val s = session ?: return null
        val emu = s.emulator ?: return null
        val text = emu.getScreen().getTranscriptText()
        if (text.isBlank()) return "(终端无内容)"
        return "终端完整输出（${text.length} 字符）：\n" + text
    }

    fun writeTtyText(command: String): Boolean {
        val s = session ?: return false
        val bytes = (command + "\n").toByteArray(Charsets.UTF_8)
        s.write(bytes, 0, bytes.size)
        return true
    }

    /* ============ 输入 ============ */
    private fun sendCommand(input: EditText) {
        // 清理输入框文本末尾可能残留的换行/回车，避免与追加的 \n 叠加导致换行两次
        val text = input.text.toString().trimEnd('\n', '\r')
        if (text.isBlank()) return
        val s = session ?: return
        input.setText("")
        if (ctrlMode || altMode) {
            for (ch in text) {
                if (ch.code < 128) {
                    terminalView?.inputCodePoint(
                        TerminalView.KEY_EVENT_SOURCE_VIRTUAL_KEYBOARD,
                        ch.code, false, false
                    )
                } else {
                    val bytes = ch.toString().toByteArray(Charsets.UTF_8)
                    s.write(bytes, 0, bytes.size)
                }
            }
            ctrlMode = false
            altMode = false
            panelView?.findViewById<TextView>(R.id.ft_key_ctrl)?.setTextColor(Color.parseColor("#E8E8E8"))
            panelView?.findViewById<TextView>(R.id.ft_key_alt)?.setTextColor(Color.parseColor("#E8E8E8"))
        } else {
            s.write(text.toByteArray(Charsets.UTF_8), 0, text.length)
            s.write("\n".toByteArray(Charsets.UTF_8), 0, 1)
        }
    }

    private fun sendRaw(raw: String) {
        val s = session ?: return
        s.write(raw.toByteArray(Charsets.UTF_8), 0, raw.length)
    }

    /* ============ TerminalSessionClient ============ */
    override fun onTextChanged(changedSession: TerminalSession) {
        mainHandler.post {
            val tv = terminalView ?: return@post
            if (changedSession === session) {
                tv.onScreenUpdated()
            }
        }
    }

    override fun onTitleChanged(changedSession: TerminalSession) {}
    override fun onSessionFinished(finishedSession: TerminalSession) {}
    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("terminal", text))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = cm.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).coerceToText(this).toString()
            session?.write(text.toByteArray(Charsets.UTF_8), 0, text.length)
        }
    }

    override fun onBell(session: TerminalSession) {}
    override fun onColorsChanged(session: TerminalSession) {}
    override fun onTerminalCursorStateChange(state: Boolean) {}
    override fun setTerminalShellPid(session: TerminalSession, pid: Int) {}
    override fun logError(tag: String, message: String) {}
    override fun logWarn(tag: String, message: String) {}
    override fun logInfo(tag: String, message: String) {}
    override fun logDebug(tag: String, message: String) {}
    override fun logVerbose(tag: String, message: String) {}
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {}
    override fun logStackTrace(tag: String, e: Exception) {}
    override fun getTerminalCursorStyle(): Int = com.termux.terminal.TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK

    private fun buildKeepAliveNotification(): android.app.Notification {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val ch = android.app.NotificationChannel(
                "webcode_fterm", "终端小窗保活", android.app.NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(android.app.NotificationManager::class.java).createNotificationChannel(ch)
        }
        val open = android.app.PendingIntent.getActivity(
            this, 0,
            Intent(this, com.webcode.app.ui.LocalSetupActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE
        )
        return androidx.core.app.NotificationCompat.Builder(this, "webcode_fterm")
            .setSmallIcon(R.drawable.ic_robot)
            .setContentTitle("WebCode 终端小窗运行中")
            .setContentText("点击悬浮球展开终端（开发中）")
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
