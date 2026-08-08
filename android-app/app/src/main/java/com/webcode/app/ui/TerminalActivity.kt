package com.webcode.app.ui

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import com.webcode.app.R
import com.webcode.app.termux.DiagLog
import com.webcode.app.termux.TerminalPty
import com.webcode.app.termux.TermuxRuntime

/**
 * Termux 风格终端 —— 使用 termux-app 官方 TerminalEmulator + TerminalView 源码。
 * 多会话、扩展键、文本选取（原生支持）与 Termux 一致。
 */
class TerminalActivity : AppCompatActivity(), TerminalSessionClient {

    companion object {
        @Volatile
        private var instance: TerminalActivity? = null

        fun current(): TerminalActivity? = instance

        /** AI 工具入口：读取当前终端屏幕+历史文本（需设置中开启 tty 权限） */
        fun readTty(maxLines: Int = 200): String? {
            val act = instance ?: return null
            return act.readTtyText(maxLines)
        }

        /** AI 工具入口：读取终端完整输出原文（无长度上限） */
        fun readTtyRaw(): String? {
            val act = instance ?: return null
            return act.readTtyRaw()
        }

        /** AI 工具入口：向当前终端 tty 注入命令并回车执行（需设置中开启 tty 权限） */
        fun writeTty(command: String): Boolean {
            val act = instance ?: return false
            return act.writeTtyText(command)
        }
    }

    private lateinit var sessionBar: LinearLayout
    private lateinit var inputView: EditText

    private val sessions = mutableListOf<TermSessionHolder>()
    private var currentId = -1

    private var ctrlMode = false
    private var altMode = false
    private var history = mutableListOf<String>()
    private var historyIndex = -1

    private var frame: FrameLayout? = null

    private var fontSize = 14f

    private class TermSessionHolder(
        val id: Int,
        val terminalView: TerminalView,
        val session: TerminalSession,
        val process: Process,
        val ptyFd: Int
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_terminal)
        instance = this

        sessionBar = findViewById(R.id.session_bar)
        inputView = findViewById(R.id.term_input)
        frame = findViewById(R.id.term_frame)

        // 扩展键
        findViewById<View>(R.id.key_esc).setOnClickListener { sendRaw("\u001b") }
        findViewById<View>(R.id.key_tab).setOnClickListener { sendRaw("\t") }
        findViewById<View>(R.id.key_ctrl).setOnClickListener {
            ctrlMode = !ctrlMode
            altMode = false
            (it as TextView).setTextColor(if (ctrlMode) Color.parseColor("#7c6cff") else Color.parseColor("#E8E8E8"))
            findViewById<TextView>(R.id.key_alt).setTextColor(Color.parseColor("#E8E8E8"))
        }
        findViewById<View>(R.id.key_alt).setOnClickListener {
            altMode = !altMode
            ctrlMode = false
            (it as TextView).setTextColor(if (altMode) Color.parseColor("#7c6cff") else Color.parseColor("#E8E8E8"))
            findViewById<TextView>(R.id.key_ctrl).setTextColor(Color.parseColor("#E8E8E8"))
        }
        findViewById<View>(R.id.key_up).setOnClickListener { historyNav(-1) }
        findViewById<View>(R.id.key_down).setOnClickListener { historyNav(1) }
        findViewById<View>(R.id.key_left).setOnClickListener { sendRaw("\u001b[D") }
        findViewById<View>(R.id.key_right).setOnClickListener { sendRaw("\u001b[C") }
        findViewById<View>(R.id.key_slash).setOnClickListener { insertText("/") }
        findViewById<View>(R.id.key_minus).setOnClickListener { insertText("-") }
        findViewById<View>(R.id.key_pipe).setOnClickListener { insertText("|") }
        findViewById<View>(R.id.key_amp).setOnClickListener { insertText("&") }
        findViewById<View>(R.id.key_tilde).setOnClickListener { insertText("~") }
        findViewById<View>(R.id.key_new).setOnClickListener { newSession() }

        findViewById<View>(R.id.key_input).setOnClickListener { toggleInputRow() }

        findViewById<View>(R.id.term_send).setOnClickListener { sendCommand() }
        inputView.setOnEditorActionListener { _, _, _ ->
            sendCommand()
            true
        }
        // 打开终端不自动弹键盘/抢焦点（由用户点击终端或 ⌨ 输入框时才弹出）

        newSession()
    }

    /** 快捷栏 ⌨ 按钮：弹出/收起底部命令输入框 */
    private fun toggleInputRow() {
        val row = findViewById<View>(R.id.term_input_row)
        val showing = row.visibility == View.VISIBLE
        try {
            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                    as android.view.inputmethod.InputMethodManager
            if (showing) {
                row.visibility = View.GONE
                // 收起时清掉输入框焦点，避免键盘字符继续被隐藏的输入框吃掉
                inputView.clearFocus()
                val h = sessions.find { it.id == currentId }
                if (h != null) h.terminalView.requestFocus()
                imm.hideSoftInputFromWindow(inputView.windowToken, 0)
            } else {
                row.visibility = View.VISIBLE
                inputView.requestFocus()
                imm.showSoftInput(inputView, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
        } catch (e: Exception) {
            row.visibility = if (showing) View.GONE else View.VISIBLE
        }
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        for (h in sessions) {
            try {
                h.session.finishIfRunning()
            } catch (e: Exception) {
            }
        }
        super.onDestroy()
    }

    /* ============ AI tty 读写（需设置中开启权限） ============ */
    private fun readTtyText(maxLines: Int): String? {
        val h = sessions.find { it.id == currentId } ?: return null
        val emu = h.session.emulator ?: return null
        val text = emu.getScreen().getTranscriptText()
        if (text.isBlank()) return "(终端无内容)"
        val lines = text.lines()
        val shown = if (lines.size > maxLines) lines.takeLast(maxLines) else lines
        val body = shown.joinToString("\n")
        // 不截断内容：超长时如实告知 AI，并提供 tty_read_raw 让 AI 决定是否读原文
        return if (body.length > 30000) {
            "终端输出过长（最近 ${shown.size} 行共 ${body.length} 字符，已超出单次传输上限）。" +
                "如需完整原文，请调用 tty_read_raw 工具读取全部内容。\n" +
                "（以下为最近 ${shown.size} 行的开头部分）\n" + body.take(3000)
        } else {
            "终端最近 ${shown.size}/${lines.size} 行：\n" + body
        }
    }

    /** 读取终端完整输出原文（无长度上限，供 AI 在 tty_read 提示过长时按需调用） */
    private fun readTtyRaw(): String? {
        val h = sessions.find { it.id == currentId } ?: return null
        val emu = h.session.emulator ?: return null
        val text = emu.getScreen().getTranscriptText()
        if (text.isBlank()) return "(终端无内容)"
        return "终端完整输出（${text.length} 字符）：\n" + text
    }

    private fun writeTtyText(command: String): Boolean {
        val h = sessions.find { it.id == currentId } ?: return false
        val bytes = (command + "\n").toByteArray(Charsets.UTF_8)
        h.session.write(bytes, 0, bytes.size)
        return true
    }

    /* ============ 会话管理 ============ */
    private fun newSession() {
        val holder = createTermSession()
        if (holder == null) {
            android.widget.Toast.makeText(this, "终端启动失败，请确认已安装 Ubuntu rootfs", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        sessions.add(holder)
        frame?.addView(
            holder.terminalView,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        switchSession(holder.id)
        if (sessions.size == 1) maybePromptAptUpdate()
    }

    /** 首次进入终端时提醒运行 apt-get update（确认后自动插入 tty 执行） */
    private fun maybePromptAptUpdate() {
        try {
            val prefs = getSharedPreferences("terminal", MODE_PRIVATE)
            if (prefs.getBoolean("apt_update_prompted", false)) return
            prefs.edit().putBoolean("apt_update_prompted", true).apply()
            val lists = java.io.File(TermuxRuntime.rootfsDir, "var/lib/apt/lists")
            if (lists.exists() && lists.listFiles()?.isNotEmpty() == true) return
            val s = sessions.find { it.id == currentId } ?: return
            android.app.AlertDialog.Builder(this)
                .setTitle("软件源更新")
                .setMessage("首次使用建议运行 apt-get update 刷新软件源索引，之后才能用 apt 安装 python3 等软件。是否现在运行？")
                .setPositiveButton("运行") { _, _ ->
                    val cmd = "apt-get update\n"
                    s.session.write(cmd.toByteArray(Charsets.UTF_8), 0, cmd.length)
                }
                .setNegativeButton("跳过", null)
                .show()
        } catch (e: Exception) {
        }
    }

    private fun createTermSession(): TermSessionHolder? {
        try {
            // 1) 创建 PTY + 启动 proot bash（slave 重定向）
            val nameBuf = ByteArray(64)
            val masterFd = TerminalPty.ptyOpen(nameBuf)
            if (masterFd < 0) return null
            val slavePath = String(nameBuf, 0, nameBuf.indexOf(0).takeIf { it > 0 } ?: nameBuf.size, Charsets.UTF_8)

            val root = java.io.File(TermuxRuntime.rootfsDir)
            if (!java.io.File(root, "bin/bash").exists()) {
                TerminalPty.ptyClose(masterFd)
                return null
            }
            val workspace = java.io.File(filesDir, "workspace")
            workspace.mkdirs()
            val rootHome = java.io.File(root, "root")
            rootHome.mkdirs()
            val hostTmp = java.io.File(TermuxRuntime.prefixDir, "tmp")
            hostTmp.mkdirs()
            TermuxRuntime.ensureDns()

            // dpkg 权限根治：/var/lib/dpkg 绑定到宿主 App 私有目录（App 完全可控、100% 可写），
            // 彻底绕开 rootfs 镜像目录权限问题（否则 dpkg 报 status-old Permission denied）
            val hostDpkg = java.io.File(TermuxRuntime.ensureHostDpkgDir(this))

            val args = mutableListOf(
                TermuxRuntime.binDir + "/proot",
                "--link2symlink",  // Android 上 hard link 受限，dpkg 备份 status-old 用 link() 会 EACCES
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
            try {
                val rc = java.io.File(rootHome, ".webcode_rc")
                rc.writeText(
                    "set +m\n" +
                    "PS1='\\[\\e[1;32m\\]webcode@ubuntu\\[\\e[0m\\]:\\[\\e[1;34m\\]\\w\\[\\e[0m\\]# '\n" +
                    "[ -f ~/.bashrc ] && . ~/.bashrc 2>/dev/null; PS1='\\[\\e[1;32m\\]webcode@ubuntu\\[\\e[0m\\]:\\[\\e[1;34m\\]\\w\\[\\e[0m\\]# '\n" +
                    "command -v python >/dev/null 2>&1 || alias python=python3 2>/dev/null\n" +
                    "if [ -n \"$(ls -A /var/lib/dpkg/updates 2>/dev/null | grep -v '^lock$')\" ]; then\n" +
                    "  echo '[WebCode] 检测到 dpkg 中断状态，正在修复...'\n" +
                    "  export DEBIAN_FRONTEND=noninteractive\n" +
                    "  dpkg --configure -a || { echo '[WebCode] dpkg --configure -a 失败，清理中断残留后重试'; rm -f /var/lib/dpkg/updates/* 2>/dev/null; dpkg --configure -a; }\n" +
                    "  echo '[WebCode] dpkg 修复完成'\n" +
                    "fi\n" +
                    "touch /var/lib/dpkg/.wtest 2>/dev/null && { rm -f /var/lib/dpkg/.wtest; } || { echo '[WebCode] /var/lib/dpkg 不可写，请重开终端（App 将自动修复权限）'; }\n"
                )
            } catch (e: Exception) {
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

            // 原生 root 模式：以 root 身份运行 proot + 挂载手机根目录到 /mnt/root
            val rootMode = com.webcode.app.local.LocalEngine.rootMode(this)
            val startArgs: List<String>
            if (rootMode) {
                try {
                    java.io.File(root, "mnt/root").mkdirs()
                } catch (e: Exception) {
                }
                args.add("-b"); args.add("/:/mnt/root")
                startArgs = listOf("su", "-c", "exec " + TermuxRuntime.buildCmdLine(args))
            } else {
                startArgs = args
            }

            val pb = ProcessBuilder(startArgs)
            pb.environment().putAll(env)
            pb.redirectErrorStream(true)
            val process = pb.start()

            // 2) termux TerminalSession（接我们的 pty fd）
            val session = TerminalSession(masterFd, process, null, this)
            // 3) TerminalView
            val view = TerminalView(this, null)
            view.setTerminalViewClient(termViewClient)
            view.setFocusable(true)
            view.setFocusableInTouchMode(true)
            view.setTextSize(fontSize.toInt())
            view.attachSession(session)

            return TermSessionHolder(nextId(), view, session, process, masterFd)
        } catch (e: Exception) {
            DiagLog.log(this, "Term", "创建终端失败: ${e.message ?: e.javaClass.simpleName}")
            return null
        }
    }

    @Volatile
    private var idCounter = 1
    private fun nextId(): Int = idCounter++

    private fun switchSession(id: Int) {
        currentId = id
        for (h in sessions) {
            h.terminalView.visibility = if (h.id == id) View.VISIBLE else View.GONE
        }
        renderSessionBar()
    }

    private fun renderSessionBar() {
        sessionBar.removeAllViews()
        for (h in sessions) {
            val tv = TextView(this)
            tv.text = "会话 ${h.id}"
            tv.textSize = 12f
            tv.typeface = Typeface.MONOSPACE
            tv.setPadding(14, 8, 14, 8)
            tv.setTextColor(if (h.id == currentId) Color.parseColor("#7c6cff") else Color.parseColor("#E8E8E8"))
            tv.setOnClickListener { switchSession(h.id) }
            tv.setOnLongClickListener {
                killSession(h.id)
                true
            }
            sessionBar.addView(tv)
        }
    }

    private fun killSession(id: Int) {
        val h = sessions.find { it.id == id } ?: return
        try {
            h.session.finishIfRunning()
        } catch (e: Exception) {
        }
        frame?.removeView(h.terminalView)
        sessions.remove(h)
        renderSessionBar()
        if (sessions.isEmpty()) newSession() else switchSession(sessions.last().id)
    }

    /* ============ 输入 ============ */
    private fun resetModifiers() {
        ctrlMode = false
        altMode = false
        findViewById<TextView>(R.id.key_ctrl).setTextColor(Color.parseColor("#E8E8E8"))
        findViewById<TextView>(R.id.key_alt).setTextColor(Color.parseColor("#E8E8E8"))
    }

    private fun sendCommand() {
        val text = inputView.text.toString()
        if (text.isBlank()) return
        val s = sessions.find { it.id == currentId } ?: return
        history.add(text)
        historyIndex = history.size
        inputView.setText("")
        inputView.post {
            // 输入框折叠时不要抢焦点（否则输入被隐藏的输入框吃掉、tty 无显示）
            if (findViewById<View>(R.id.term_input_row).visibility == View.VISIBLE) {
                inputView.requestFocus()
                try {
                    val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                            as android.view.inputmethod.InputMethodManager
                    imm.showSoftInput(inputView, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                } catch (e: Exception) {
                }
            } else {
                inputView.clearFocus()
                s.terminalView.requestFocus()
            }
        }
        if (ctrlMode || altMode) {
            // Ctrl/Alt 模式下逐字符经 inputCodePoint 转换（Ctrl+C 等），
            // 不追加回车：组合键本身即指令，再发 \n 会把光标弹回行首
            for (ch in text) {
                if (ch.code < 128) {
                    s.terminalView.inputCodePoint(
                        com.termux.view.TerminalView.KEY_EVENT_SOURCE_VIRTUAL_KEYBOARD,
                        ch.code, false, false
                    )
                } else {
                    val bytes = ch.toString().toByteArray(Charsets.UTF_8)
                    s.session.write(bytes, 0, bytes.size)
                }
            }
            resetModifiers()
        } else {
            s.session.write(text.toByteArray(Charsets.UTF_8), 0, text.length)
            s.session.write("\n".toByteArray(Charsets.UTF_8), 0, 1)
        }
    }

    private fun sendRaw(raw: String) {
        val s = sessions.find { it.id == currentId } ?: return
        s.session.write(raw.toByteArray(Charsets.UTF_8), 0, raw.length)
    }

    private fun insertText(t: String) {
        val start = inputView.selectionStart.coerceAtLeast(0)
        val end = inputView.selectionEnd.coerceAtLeast(start)
        inputView.text.replace(start, end, t)
        inputView.setSelection(start + t.length)
    }

    private fun historyNav(dir: Int) {
        if (history.isEmpty()) return
        historyIndex += dir
        historyIndex = historyIndex.coerceIn(0, history.size)
        inputView.setText(if (historyIndex < history.size) history[historyIndex] else "")
        inputView.setSelection(inputView.text.length)
    }

    private val termViewClient = object : TerminalViewClient {
        override fun onSingleTapUp(e: android.view.MotionEvent) {
            val h = sessions.find { it.id == currentId } ?: return
            // 强制把焦点从输入框移走，否则键盘字符会被输入框吃掉（tty 无显示）
            inputView.clearFocus()
            h.terminalView.requestFocus()
            // post 二次确认：IME/焦点竞态下可能被输入框抢回
            h.terminalView.post {
                if (!h.terminalView.hasFocus()) {
                    inputView.clearFocus()
                    h.terminalView.requestFocus()
                }
            }
            try {
                val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                        as android.view.inputmethod.InputMethodManager
                imm.showSoftInput(h.terminalView, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            } catch (ex: Exception) {
            }
        }

        override fun shouldBackButtonBeMappedToEscape(): Boolean = false
        override fun shouldEnforceCharBasedInput(): Boolean = false
        override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
        override fun isTerminalViewSelected(): Boolean = true
        override fun copyModeChanged(copyMode: Boolean) {}
        override fun onKeyDown(keyCode: Int, e: android.view.KeyEvent, session: TerminalSession): Boolean = false
        override fun onKeyUp(keyCode: Int, e: android.view.KeyEvent): Boolean = false
        override fun onLongPress(event: android.view.MotionEvent): Boolean = false
        override fun readControlKey(): Boolean = ctrlMode
        override fun readAltKey(): Boolean = altMode
        override fun readShiftKey(): Boolean = false
        override fun readFnKey(): Boolean = false
        override fun onModifierKeysConsumed() {
            // 单次语义：Ctrl/Alt 被消费一个字符后自动回位
            runOnUiThread { resetModifiers() }
        }
        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false
        override fun onEmulatorSet() {}
        override fun onScale(scale: Float): Float {
            fontSize = (fontSize * scale).coerceIn(8f, 48f)
            for (h in sessions) {
                h.terminalView.setTextSize(fontSize.toInt())
            }
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

    /* ============ TerminalSessionClient ============ */
    override fun onTextChanged(changedSession: TerminalSession) {
        runOnUiThread {
            val h = sessions.find { it.session === changedSession } ?: return@runOnUiThread
            if (h.id == currentId) {
                h.terminalView.onScreenUpdated()
            }
        }
    }

    override fun onTitleChanged(changedSession: TerminalSession) {}
    override fun onSessionFinished(finishedSession: TerminalSession) {
        runOnUiThread {
            val h = sessions.find { it.session === finishedSession } ?: return@runOnUiThread
            h.terminalView.post {
                android.widget.Toast.makeText(this, "会话 ${h.id} 已结束（长按会话名删除）", android.widget.Toast.LENGTH_SHORT).show()
            }
            frame?.removeView(h.terminalView)
            sessions.remove(h)
            renderSessionBar()
            if (sessions.isEmpty()) newSession() else switchSession(sessions.last().id)
        }
    }

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
}
