package com.webcode.app.termux

import android.content.Context
import java.io.File

/**
 * 内嵌 Termux 运行时管理器：
 *  - 下载并安装官方 bootstrap（bash / coreutils / apt 等 Linux 用户态）
 *  - 在应用内直接执行 sh 命令（ProcessBuilder + Termux 环境变量）
 * 移植自 termux-app 官方源码（见 termux-app-src/，GPL-3.0）
 */
object LocalAgentManager {

    fun isInstalled(context: Context): Boolean {
        TermuxRuntime.init(context)
        return TermuxRuntime.isRootfsInstalled()
    }

    fun bashPath(): String = TermuxRuntime.binDir + "/bash"

    const val ROOTFS_VERSION = "26.04"
    const val ROOTFS_FILE = "ubuntu-base-26.04-arm64.tar.gz"

    /** rootfs 下载地址（多源回退） */
    fun rootfsUrls(): List<String> {
        val official = "https://github.com/dodkxhnc/webcode-local/releases/download/ubuntu-26.04/$ROOTFS_FILE"
        return buildList {
            add(official)
            add("https://ghfast.top/$official")
            add("https://ghproxy.net/$official")
            add("https://mirror.ghproxy.com/$official")
            add("https://gh-proxy.com/$official")
            add("https://ghps.cc/$official")
        }
    }

    /** 下载并安装 Ubuntu rootfs（~35MB，多源回退） */
    fun installRootfs(
        context: Context,
        onProgress: (done: Long, total: Long) -> Unit
    ): String {
        TermuxRuntime.init(context)
        // rootfs 模式不依赖 bootstrap，只需 proot（assets 自带，零下载）
        TermuxRuntime.ensureProot()
        val bash = java.io.File(TermuxRuntime.rootfsDir, "bin/bash")
        if (TermuxRuntime.isRootfsInstalled() && bash.exists() && bash.canExecute()) {
            return "Ubuntu rootfs 已安装，跳过"
        }
        // 已安装但 bash 不可执行（旧版解压未保留权限位）→ 用缓存 tar 直接重解压修复
        if (bash.exists() && !bash.canExecute()) {
            val cached = java.io.File(context.cacheDir, ROOTFS_FILE)
            if (cached.exists() && cached.length() > 10_000_000) {
                java.io.File(TermuxRuntime.rootfsDir).deleteRecursively()
                TermuxRuntime.extractRootfs(cached)
                if (java.io.File(TermuxRuntime.rootfsDir, "bin/bash").canExecute()) {
                    return "已用缓存修复 rootfs 权限（无需重新下载）"
                }
            }
            java.io.File(TermuxRuntime.rootfsDir).deleteRecursively()
        }
        val target = java.io.File(context.cacheDir, ROOTFS_FILE)
        var lastError: String? = null
        for (url in rootfsUrls()) {
            try {
                lastError = TermuxRuntime.downloadTo(url, target, onProgress)
                if (lastError == null) {
                    if (target.length() < 10_000_000) {
                        lastError = "文件过小（${target.length() / 1024 / 1024}MB），可能不完整"
                    } else {
                        break
                    }
                }
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
            }
            target.delete()
            onProgress(-1, -1)
        }
        if (target.length() < 10_000_000) {
            throw RuntimeException("rootfs 下载失败：$lastError（已尝试 ${rootfsUrls().size} 个源）")
        }
        TermuxRuntime.extractRootfs(target)
        return "Ubuntu 26.04 LTS rootfs 安装完成"
    }

    /**
     * 在 Termux 环境中执行 sh 命令
     * @param cwd 工作目录（默认 Termux home）
     */
    fun runCommand(
        context: Context,
        command: String,
        cwd: File? = null,
        timeoutMs: Long = 120_000
    ): String {
        TermuxRuntime.init(context)
        val useRootfs = com.webcode.app.local.LocalEngine.useRootfs(context)
        if (useRootfs) {
            // rootfs 模式只依赖 proot（assets 自带，零下载），不依赖 bootstrap
            TermuxRuntime.ensureProot()
            if (!TermuxRuntime.isRootfsInstalled()) {
                return "Ubuntu rootfs 未安装，请先在设置页下载或导入（或关闭 rootfs 模式）"
            }
            if (!File(TermuxRuntime.binDir, "proot").exists()) {
                return "proot 缺失（assets 安装失败），请重装 APK 或检查存储"
            }
            return runInRootfs(context, command, cwd, timeoutMs)
        }
        // 未开启 rootfs：提示开启
        return "请先在设置页勾选「使用 Ubuntu rootfs 执行命令」并安装 rootfs"
    }

    /** 在 Ubuntu rootfs 中启动持久进程（MCP stdio 服务器用），保留 stdin/stdout 管道 */
    fun mcpSpawn(context: Context, command: String): ProcessBuilder {
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
        val mounts = com.webcode.app.local.LocalEngine.mountPaths(context)
        for ((idx, mp) in mounts.withIndex()) {
            val src = java.io.File(mp)
            if (!src.exists()) continue
            val guestPath = if (idx == 0) "/mnt/external" else "/mnt/external-$idx"
            val targetDir = java.io.File(root, guestPath.trimStart('/'))
            targetDir.mkdirs()
            args.add("-b"); args.add("${src.absolutePath}:$guestPath")
        }
        args.add("-w"); args.add("/root")
        args.add("/bin/bash"); args.add("-c")
        args.add("export TMPDIR=/tmp; cd /workspace && exec $command")

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

        val pb = ProcessBuilder(args)
        pb.environment().putAll(env)
        return pb
    }

    /** 在 Ubuntu rootfs 中执行（proot -r rootfs，自带完整 FHS，无硬编码路径问题） */
    private fun runInRootfs(
        context: Context,
        command: String,
        cwd: java.io.File?,
        timeoutMs: Long
    ): String {
        val prootBin = java.io.File(TermuxRuntime.binDir, "proot")
        val root = java.io.File(TermuxRuntime.rootfsDir)
        val workspace = java.io.File(context.filesDir, "workspace")
        workspace.mkdirs()
        val rootHome = java.io.File(TermuxRuntime.rootfsDir, "root")
        rootHome.mkdirs()

        // 前置检查：proot 二进制与 rootfs bash
        if (!prootBin.exists()) {
            return "proot 二进制不存在: ${prootBin.absolutePath}（assets 安装失败，请查看 proot-errors.log）"
        }
        if (!prootBin.canExecute()) {
            return "proot 无执行权限: ${prootBin.absolutePath}"
        }
        val bash = java.io.File(root, "bin/bash")
        if (!bash.exists()) {
            return "rootfs 缺少 /bin/bash: ${bash.absolutePath}"
        }
        if (!bash.canExecute()) {
            // 运行时自愈：直接补执行位
            try {
                android.system.Os.chmod(bash.absolutePath, 0x1ED)
                bash.setExecutable(true, false)
            } catch (e: Exception) {
                return "rootfs 权限异常（/bin/bash 不可执行，chmod 也失败: ${e.message}）：请到设置页重新下载 rootfs"
            }
            if (!bash.canExecute()) {
                return "rootfs 权限异常（/bin/bash 仍不可执行）：请到设置页重新点「下载 Ubuntu 26.04 rootfs」"
            }
        }
        if (!rootHome.exists()) {
            return "rootfs 缺少 /root 目录: ${rootHome.absolutePath}"
        }

        val hostTmp = java.io.File(TermuxRuntime.prefixDir, "tmp")
        hostTmp.mkdirs()
        val args = mutableListOf(prootBin.absolutePath, "-0", "-r", root.absolutePath)
        // 工作区绑定到 /workspace，HOME 绑定到 /root，宿主 tmp 绑定到 /tmp
        args.add("-b"); args.add("${workspace.absolutePath}:/workspace")
        args.add("-b"); args.add("${rootHome.absolutePath}:/root")
        args.add("-b"); args.add("${hostTmp.absolutePath}:/tmp")
        // 关键：绑定宿主 /proc /dev /sys —— rootfs 没有 /proc，
        // rust-coreutils 等需要读 /proc/self/auxv，缺失会 panic（proot 虚拟 /proc 不提供 auxv）
        args.add("-b"); args.add("/proc:/proc")
        args.add("-b"); args.add("/dev:/dev")
        args.add("-b"); args.add("/sys:/sys")
        // 外部路径挂载：开启后在 /mnt/external（及 /mnt/external-N）可见
        val mounts = com.webcode.app.local.LocalEngine.mountPaths(context)
        for ((idx, mp) in mounts.withIndex()) {
            val src = java.io.File(mp)
            if (!src.exists()) continue
            val guestPath = if (idx == 0) "/mnt/external" else "/mnt/external-$idx"
            val targetDir = java.io.File(root, guestPath.trimStart('/'))
            targetDir.mkdirs()
            args.add("-b"); args.add("${src.absolutePath}:$guestPath")
        }
        args.add("-w"); args.add("/root")
        args.add("/bin/bash"); args.add("-c")
        args.add("export TMPDIR=/tmp; cd /workspace && $command")

        val env = HashMap<String, String>()
        env["PATH"] = "/usr/bin:/bin:/usr/sbin:/sbin"
        env["HOME"] = "/root"
        env["TERM"] = "xterm-256color"
        env["LANG"] = "en_US.UTF-8"
        // proot 进程自身的临时目录用宿主路径（安卓宿主无 /tmp）。
        // 关键：proot 源码只认 PROOT_TMP_DIR（不认 TMPDIR），不设则默认 P_tmpdir=/tmp 导致解压 loader 失败
        env["PROOT_TMP_DIR"] = hostTmp.absolutePath
        env["TMPDIR"] = hostTmp.absolutePath
        // 指向预置的静态 loader，避免运行时解压（彻底规避 noexec 临时目录问题）
        env["PROOT_LOADER"] = TermuxRuntime.prefixDir + "/libexec/proot/loader"
        env["PROOT_LOADER_32"] = TermuxRuntime.prefixDir + "/libexec/proot/loader32"
        env["LD_LIBRARY_PATH"] = TermuxRuntime.prefixDir + "/lib"
        env["ANDROID_ROOT"] = System.getenv("ANDROID_ROOT") ?: "/system"
        env["ANDROID_DATA"] = System.getenv("ANDROID_DATA") ?: "/data"

        return try {
            val pb = ProcessBuilder(args)
            pb.environment().putAll(env)
            val p = pb.redirectErrorStream(true).start()
            val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
            if (!p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                p.destroyForcibly()
            }
            if (p.exitValue() != 0 && out.isBlank()) {
                "proot 退出码 ${p.exitValue()}（无输出，可能被系统限制 ptrace）"
            } else {
                out
            }
        } catch (e: Exception) {
            "proot 启动失败: ${e.message ?: e.javaClass.simpleName}"
        }
    }
}
