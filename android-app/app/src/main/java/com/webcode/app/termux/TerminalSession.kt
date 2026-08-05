package com.webcode.app.termux

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 终端会话：真实 PTY（伪终端）实现。
 * 用 android.system.Os 创建 pty master/slave，bash 的 stdin/stdout 重定向到 pty slave，
 * 获得完整终端语义：无 job control 警告、tty 回显、交互程序可用。
 */
class TerminalSession(private val context: Context, val id: Int) {

    private var masterFd: Int = -1
    private var slavePath: String = ""
    private var process: Process? = null
    private var readThread: Thread? = null
    private val alive = AtomicBoolean(false)

    val buffer = StringBuilder()
    var onOutput: ((String) -> Unit)? = null
    var onExit: ((Int) -> Unit)? = null

    fun start() {
        if (alive.get()) return
        try {
            // 1) 创建 PTY（原生库）
            val nameBuf = ByteArray(64)
            masterFd = TerminalPty.ptyOpen(nameBuf)
            if (masterFd < 0) throw RuntimeException("posix_openpt 失败")
            slavePath = String(nameBuf, 0, nameBuf.indexOf(0).takeIf { it > 0 } ?: nameBuf.size, Charsets.UTF_8)
            TerminalPty.ptySetSize(masterFd, 24, 80)

            // 2) 组装 proot 命令，bash 的 stdin/stdout/stderr 重定向到 pty slave
            val root = java.io.File(TermuxRuntime.rootfsDir)
            val workspace = java.io.File(context.filesDir, "workspace")
            workspace.mkdirs()
            val rootHome = java.io.File(root, "root")
            rootHome.mkdirs()
            val hostTmp = java.io.File(TermuxRuntime.prefixDir, "tmp")
            hostTmp.mkdirs()

            val args = mutableListOf(TermuxRuntime.binDir + "/proot", "-0", "-r", root.absolutePath)
            args.add("-b"); args.add("${workspace.absolutePath}:/workspace")
            args.add("-b"); args.add("${rootHome.absolutePath}:/root")
            args.add("-b"); args.add("${hostTmp.absolutePath}:/tmp")
            args.add("-b"); args.add("/proc:/proc")
            args.add("-b"); args.add("/dev:/dev")
            args.add("-b"); args.add("/sys:/sys")
            TermuxRuntime.ensureDns()
            val mounts = com.webcode.app.local.LocalEngine.mountPaths(context)
            for ((idx, mp) in mounts.withIndex()) {
                val src = java.io.File(mp)
                if (!src.exists()) continue
                val guestPath = if (idx == 0) "/mnt/external" else "/mnt/external-$idx"
                java.io.File(root, guestPath.trimStart('/')).mkdirs()
                args.add("-b"); args.add("${src.absolutePath}:$guestPath")
            }
            args.add("-w"); args.add("/root")

            // 外层 bash -c：exec 交互式 bash 并把 fd 指向 pty slave（真实 TTY 语义）
            try {
                val rc = java.io.File(rootHome, ".webcode_rc")
                rc.writeText(
                    "set +m\n" +
                    "PS1='\\[\\e[1;32m\\]webcode@ubuntu\\[\\e[0m\\]:\\[\\e[1;34m\\]\\w\\[\\e[0m\\]# '\n" +
                    "[ -f ~/.bashrc ] && . ~/.bashrc 2>/dev/null; PS1='\\[\\e[1;32m\\]webcode@ubuntu\\[\\e[0m\\]:\\[\\e[1;34m\\]\\w\\[\\e[0m\\]# '\n"
                )
            } catch (e: Exception) {
            }
            // setsid 开新会话：bash 成为会话组长，pty slave 自动成为控制终端 → job control 正常、无警告
            val inner = "exec /usr/bin/setsid /bin/bash --rcfile /root/.webcode_rc -i <$slavePath >$slavePath 2>&1"
            args.add("/bin/bash"); args.add("-c"); args.add(inner)

            val env = HashMap<String, String>()
            env["PATH"] = "/usr/bin:/bin:/usr/sbin:/sbin"
            env["HOME"] = "/root"
            env["TERM"] = "xterm-256color"
            env["LANG"] = "en_US.UTF-8"
            env["PROOT_TMP_DIR"] = hostTmp.absolutePath
            env["TMPDIR"] = hostTmp.absolutePath
            env["PROOT_LOADER"] = TermuxRuntime.prefixDir + "/libexec/proot/loader"
            env["PROOT_LOADER_32"] = TermuxRuntime.prefixDir + "/libexec/proot/loader32"
            env["LD_LIBRARY_PATH"] = TermuxRuntime.prefixDir + "/lib"
            env["COLUMNS"] = "80"
            env["LINES"] = "24"

            val pb = ProcessBuilder(args)
            pb.environment().putAll(env)
            pb.redirectErrorStream(true)
            val p = pb.start()
            process = p
            alive.set(true)

            // 3) 读取线程：从 pty master 读输出
            readThread = Thread {
                try {
                    val buf = ByteArray(4096)
                    while (alive.get()) {
                        val n = try {
                            TerminalPty.ptyRead(masterFd, buf, 0, buf.size)
                        } catch (e: Exception) {
                            -1
                        }
                        if (n <= 0) break
                        val chunk = String(buf, 0, n, Charsets.UTF_8)
                        synchronized(buffer) {
                            buffer.append(chunk)
                            if (buffer.length > 300_000) {
                                buffer.delete(0, buffer.length - 250_000)
                            }
                        }
                        onOutput?.invoke(chunk)
                    }
                } catch (e: Exception) {
                } finally {
                    alive.set(false)
                    val code = try {
                        p.waitFor()
                        p.exitValue()
                    } catch (e: Exception) {
                        -1
                    }
                    onExit?.invoke(code)
                }
            }.apply { isDaemon = true; start() }
        } catch (e: Exception) {
            DiagLog.log(context, "Term", "PTY 会话启动失败: ${e.message ?: e.javaClass.simpleName}")
            onExit?.invoke(-1)
        }
    }

    fun writeLine(cmd: String) {
        writeRaw(cmd + "\n")
    }

    fun writeRaw(text: String) {
        if (!alive.get() || masterFd < 0) return
        try {
            val bytes = text.toByteArray(Charsets.UTF_8)
            TerminalPty.ptyWrite(masterFd, bytes, 0, bytes.size)
        } catch (e: Exception) {
        }
    }

    fun isAlive(): Boolean = alive.get()

    fun destroy() {
        alive.set(false)
        try {
            process?.destroy()
        } catch (e: Exception) {
        }
        try {
            if (masterFd >= 0) TerminalPty.ptyClose(masterFd)
        } catch (e: Exception) {
        }
        masterFd = -1
        process = null
    }

    companion object {
        @Volatile
        private var nextId = 1

        fun create(context: Context): TerminalSession =
            TerminalSession(context, nextId++)
    }
}
