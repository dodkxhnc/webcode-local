package com.webcode.app.termux

/**
 * 内嵌 Termux 运行时（部分移植自 termux-app 官方源码）
 * https://github.com/termux/termux-app (GPL-3.0)
 * 移植自: TermuxInstaller.java / TermuxShellEnvironment.java / TermuxConstants.java
 *
 * 职责：
 *  - 按 CPU 架构下载官方 bootstrap 包（GitHub releases）
 *  - 按 TermuxInstaller 相同流程解压（SYMLINKS.txt 软链 / chmod / 重命名）
 *  - 构建 Termux 环境变量（PREFIX/HOME/PATH/TMPDIR/LANG…）
 *  - 在应用进程内直接执行 bash（无需外部 Termux app）
 */
import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

object TermuxRuntime {

    const val VERSION = "2026.02.12-r1%2Bapt.android-7"
    const val FALLBACK_VERSION = "2022.04.28-r6+apt-android-5"

    val prefixDir: String get() = "${AppFilesDir}/usr"
    val stagingDir: String get() = "${AppFilesDir}/usr-staging"
    val homeDir: String get() = "${AppFilesDir}/home"
    val tmpDir: String get() = "$prefixDir/tmp"
    val binDir: String get() = "$prefixDir/bin"

    lateinit var AppFilesDir: String
        private set

    val bootstrapUrl: String get() = bootstrapUrlFor(archName(), VERSION)

    fun init(context: Context) {
        AppFilesDir = context.filesDir.absolutePath
    }

    /** aarch64 / arm / x86_64 / i686（与 termux bootstrap 架构名一致） */
    fun archName(): String {
        for (abi in Build.SUPPORTED_ABIS) {
            when {
                abi.startsWith("arm64") -> return "aarch64"
                abi.startsWith("armeabi") -> return "arm"
                abi.startsWith("x86_64") -> return "x86_64"
                abi.startsWith("x86") -> return "i686"
            }
        }
        return "aarch64"
    }

    fun bootstrapUrlFor(arch: String, version: String): String =
        "https://github.com/termux/termux-packages/releases/download/bootstrap-$version/bootstrap-$arch.zip"

    fun isBootstrapInstalled(): Boolean =
        File(binDir, "bash").exists() && File(prefixDir, "lib").exists()

    fun homeDirExists(): Boolean = File(homeDir).exists()

    fun ensureHome() {
        File(homeDir).mkdirs()
    }

    fun ensureTmp() {
        File(tmpDir).mkdirs()
    }

    /** 下载 bootstrap zip 到缓存目录。多源回退 + SHA-256 校验，避免 GitHub 下载失败/文件损坏 */
    fun downloadBootstrap(onProgress: (Long, Long) -> Unit): File {
        val arch = archName()
        val zipFile = File(File(AppFilesDir).parentFile!!.parentFile!!, "cache/bootstrap-$arch.zip")
        zipFile.parentFile!!.mkdirs()

        // 已有缓存且校验通过则直接用
        if (zipFile.exists() && sha256(zipFile) == checksumFor(arch)) return zipFile

        val urls = buildList {
            // 1. 官方 GitHub
            add(bootstrapUrlFor(arch, VERSION))
            // 2. 国内 GitHub 加速代理（仅公共代理，不依赖任何自建服务器）
            add("https://ghfast.top/" + bootstrapUrlFor(arch, VERSION))
            add("https://ghproxy.net/" + bootstrapUrlFor(arch, VERSION))
            add("https://mirror.ghproxy.com/" + bootstrapUrlFor(arch, VERSION))
            add("https://gh-proxy.com/" + bootstrapUrlFor(arch, VERSION))
            add("https://ghps.cc/" + bootstrapUrlFor(arch, VERSION))
        }

        var lastError: String? = null
        for (url in urls) {
            try {
                lastError = downloadFrom(url, zipFile, onProgress)
                if (lastError == null) {
                    if (sha256(zipFile) == checksumFor(arch)) {
                        return zipFile
                    } else {
                        lastError = "校验和不匹配（下载损坏）"
                    }
                }
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
            }
            zipFile.delete()
            onProgress(-1, -1)
        }
        throw RuntimeException("bootstrap 下载失败：$lastError（已尝试 ${urls.size} 个源）")
    }

    private fun downloadFrom(url: String, target: File, onProgress: (Long, Long) -> Unit): String? {
        var conn: HttpURLConnection? = null
        try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 20000
            conn.readTimeout = 120000
            conn.connect()
            if (conn.responseCode != 200) {
                return "HTTP ${conn.responseCode}"
            }
            val total = conn.contentLength.toLong()
            val input = conn.inputStream
            FileOutputStream(target).use { out ->
                val buf = ByteArray(64 * 1024)
                var read: Int
                var done = 0L
                while (input.read(buf).also { read = it } != -1) {
                    out.write(buf, 0, read)
                    done += read
                    onProgress(done, total)
                }
            }
            return null
        } finally {
            conn?.disconnect()
        }
    }

    private fun sha256(f: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            var read: Int
            while (input.read(buf).also { read = it } != -1) {
                md.update(buf, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun checksumFor(arch: String): String = when (arch) {
        "aarch64" -> "ea2aeba8819e517db711f8c32369e89e7c52cee73e07930ff91185e1ab93f4f3"
        "arm" -> "a38f4d3b2f735f83be2bf54eff463e86dc32a3e2f9f861c1557c4378d249c018"
        "i686" -> "f5bc0b025b9f3b420b5fcaeefc064f888f5f22a0d6fd7090f4aac0c33eb3555b"
        "x86_64" -> "b7fd0f2e3a4de534be3144f9f91acc768630fc463eaf134ab2e64c545e834f7a"
        else -> ""
    }

    /**
     * 安装 bootstrap —— 与 termux-app TermuxInstaller 相同流程：
     * 解压到 staging → 处理 SYMLINKS.txt（"目标←链接"）→ bin 等加执行权限 → 重命名为 usr
     */
    fun installBootstrap(zipFile: File) {
        val staging = File(stagingDir)
        if (staging.exists()) staging.deleteRecursively()
        val prefix = File(prefixDir)
        if (prefix.exists()) prefix.deleteRecursively()

        staging.mkdirs()

        val symlinks = mutableListOf<Pair<String, String>>()
        ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                if (name == "SYMLINKS.txt") {
                    val text = zip.readBytes().toString(Charsets.UTF_8)
                    for (line in text.lines()) {
                        if (line.isBlank()) continue
                        val parts = line.split("←")
                        if (parts.size == 2) {
                            symlinks.add(parts[0] to "${staging.absolutePath}/${parts[1]}")
                            File(parts[1]).parentFile?.let {
                                File("${staging.absolutePath}/${it.path}").mkdirs()
                            }
                        }
                    }
                } else {
                    val target = File(staging, name)
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { out ->
                            val buf = ByteArray(64 * 1024)
                            var read: Int
                            while (zip.read(buf).also { read = it } != -1) {
                                out.write(buf, 0, read)
                            }
                        }
                        if (name.startsWith("bin/") || name.startsWith("libexec") ||
                            name.startsWith("lib/apt/apt-helper") || name.startsWith("lib/apt/methods")
                        ) {
                            // TermuxInstaller: Os.chmod(target, 0700)
                            Runtime.getRuntime().exec(arrayOf("chmod", "0700", target.absolutePath)).waitFor()
                        }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        if (symlinks.isEmpty()) throw RuntimeException("bootstrap 包中无 SYMLINKS.txt")

        for ((old, new) in symlinks) {
            val link = File(new)
            link.parentFile?.mkdirs()
            if (link.exists()) link.delete()
            Runtime.getRuntime().exec(arrayOf("ln", "-s", old, new)).waitFor()
        }

        if (!staging.renameTo(prefix)) {
            throw RuntimeException("移动 usr-staging -> usr 失败")
        }

        ensureHome()
        ensureTmp()
        // 必须：bash 若不可执行，pkg 无法工作
        Runtime.getRuntime().exec(arrayOf("chmod", "0711", binDir)).waitFor()
        Runtime.getRuntime().exec(arrayOf("chmod", "0711", "${prefixDir}/lib")).waitFor()
    }

    /** 构建 Termux 环境变量（TermuxShellEnvironment 移植） */
    fun environment(): Map<String, String> {
        val env = HashMap<String, String>()
        env["PREFIX"] = prefixDir
        env["PREFIX_CLASSPATH"] = "$prefixDir/etc/tirpc"
        env["HOME"] = homeDir
        env["TMPDIR"] = tmpDir
        env["PATH"] = "$binDir:$binDir/applets"
        env["LD_LIBRARY_PATH"] = "$prefixDir/lib"
        env["TERM"] = "xterm-256color"
        env["LANG"] = "en_US.UTF-8"
        env["SSL_CERT_FILE"] = "$prefixDir/etc/tls/cert.pem"
        env["PACKAGE_NAME"] = "com.webcode.app"
        env["ANDROID_ROOT"] = System.getenv("ANDROID_ROOT") ?: "/system"
        env["ANDROID_DATA"] = System.getenv("ANDROID_DATA") ?: "/data"
        env["ANDROID_EXTERNAL_STORAGE"] = System.getenv("ANDROID_EXTERNAL_STORAGE") ?: "/sdcard"
        env["SHELL"] = "$binDir/bash"
        return env
    }

    /**
     * 在 Termux 环境中执行命令，返回合并输出
     */
    fun run(cmd: String, timeoutMs: Long = 120_000, cwd: File? = null): String {
        val pb = ProcessBuilder(binDir + "/bash", "-c", cmd)
        pb.environment().putAll(environment())
        cwd?.let { pb.directory(it) }
        val p = pb.redirectErrorStream(true).start()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        if (!p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            p.destroyForcibly()
        }
        return out
    }

    /** 启动一个长驻进程（Agent 服务），输出重定向到日志文件，返回 Process */
    fun spawn(cmd: String, logFile: File): Process {
        val pb = ProcessBuilder(binDir + "/bash", "-c", cmd)
        pb.environment().putAll(environment())
        logFile.parentFile?.mkdirs()
        pb.redirectErrorStream(true)
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
        return pb.start()
    }
}
