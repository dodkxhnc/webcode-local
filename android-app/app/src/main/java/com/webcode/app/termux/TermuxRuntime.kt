package com.webcode.app.termux

/**
 * 内嵌运行时核心（移植自 termux-app 官方源码 https://github.com/termux/termux-app，GPL-3.0）：
 *  - proot 组件安装（随 APK assets 打包，零下载）
 *  - Ubuntu rootfs 解压（纯 Kotlin，无 chown、无 tar 依赖）
 *  - 通用多源下载（rootfs 用）
 *
 * 架构说明：完整 Ubuntu rootfs 自带 bash/apt/python，运行只需 proot（assets 提供），
 * 不再依赖 Termux bootstrap（已移除）。
 */
import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

object TermuxRuntime {

    val prefixDir: String get() = "${AppFilesDir}/usr"
    val homeDir: String get() = "${AppFilesDir}/home"
    val binDir: String get() = "$prefixDir/bin"
    val rootfsDir: String get() = "${AppFilesDir}/ubuntu"

    lateinit var AppFilesDir: String
        private set

    private lateinit var appContext: Context

    fun appContextOrNull(): Context? =
        if (::appContext.isInitialized) appContext else null

    fun init(context: Context) {
        AppFilesDir = context.filesDir.absolutePath
        if (!::appContext.isInitialized) appContext = context
        // 每次启动自动补齐 proot 组件（随 APK assets 打包，零下载）
        installProotComponents()
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

    fun isRootfsInstalled(): Boolean {
        if (!::AppFilesDir.isInitialized) return false
        return File(rootfsDir, "bin/bash").exists() || File(rootfsDir, "usr/bin/bash").exists()
    }

    /** 卸载 Ubuntu rootfs（删除整个 files/ubuntu） */
    fun uninstallRootfs(): Boolean {
        try {
            val target = File(rootfsDir)
            if (!target.exists()) return false
            target.deleteRecursively()
            return !target.exists()
        } catch (e: Exception) {
            return false
        }
    }

    /* ============ proot 组件（assets 自带） ============ */

    /** 安装 proot 组件：按架构从 assets 拷贝到 usr/bin + usr/lib + usr/libexec，无需 bootstrap。
     *  包括 loader（Android 链接器差异必需）。拷贝后立即验证，失败写入 proot-errors.log */
    fun installProotComponents() {
        try {
            File(binDir).mkdirs()
            File("$prefixDir/lib").mkdirs()
            File("$prefixDir/tmp").mkdirs()
            val arch = archName()
            val target = File(binDir, "proot")
            val assetName = "termux/proot-$arch"
            copyAssetTo(assetName, target)
            copyAssetTo("termux/libtalloc.so.2-$arch", File("$prefixDir/lib", "libtalloc.so.2"))
            copyAssetTo("termux/libandroid-shmem.so-$arch", File("$prefixDir/lib", "libandroid-shmem.so"))
            // 源码编译的静态 loader（避免运行时解压内嵌 loader 的 noexec/临时目录问题）
            File("$prefixDir/libexec/proot").mkdirs()
            copyAssetTo("termux/loader-$arch", File("$prefixDir/libexec/proot", "loader"))
            try {
                copyAssetTo("termux/loader32-$arch", File("$prefixDir/libexec/proot", "loader32"))
            } catch (e: Exception) {
            }
            // 关键：proot 和 loader 都必须可执行（assets 拷出默认 0644，不 chmod 会导致 execve Permission denied）
            Runtime.getRuntime().exec(arrayOf("chmod", "0755", target.absolutePath)).waitFor()
            val loaderFile = File("$prefixDir/libexec/proot/loader")
            if (loaderFile.exists()) {
                Runtime.getRuntime().exec(arrayOf("chmod", "0755", loaderFile.absolutePath)).waitFor()
            }
            val loader32File = File("$prefixDir/libexec/proot/loader32")
            if (loader32File.exists()) {
                Runtime.getRuntime().exec(arrayOf("chmod", "0755", loader32File.absolutePath)).waitFor()
            }
            if (!target.exists()) {
                writeDiag("proot 拷贝后不存在: 资产 $assetName 目标 ${target.absolutePath}")
                return
            }
            if (!target.canExecute()) {
                writeDiag("proot 无执行权限: ${target.absolutePath}")
                return
            }
            if (!loaderFile.exists() || !loaderFile.canExecute()) {
                writeDiag("loader 缺失或不可执行: ${loaderFile.absolutePath}")
            }
        } catch (e: Exception) {
            writeDiag("proot 安装异常: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun writeDiag(msg: String) {
        try {
            val ctx = appContextOrNull() ?: return
            val f = File(ctx.getExternalFilesDir(null), "proot-errors.log")
            f.parentFile?.mkdirs()
            f.appendText("时间: ${System.currentTimeMillis()} $msg\n")
        } catch (e: Exception) {
        }
    }

    /** 确保 proot 就绪（rootfs 模式唯一依赖） */
    fun ensureProot() {
        installProotComponents()
    }

    private fun copyAssetTo(asset: String, target: File) {
        target.parentFile?.mkdirs()
        appContext.assets.open(asset).use { input ->
            target.outputStream().use { out -> input.copyTo(out) }
        }
    }

    /* ============ 下载（rootfs 用，多源回退） ============ */

    /** 通用下载：成功返回 null，失败返回原因 */
    fun downloadTo(url: String, target: File, onProgress: (Long, Long) -> Unit): String? =
        downloadFrom(url, target, onProgress)

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

    /* ============ Ubuntu rootfs 解压（纯 Kotlin，无 chown/无 tar） ============ */

    /**
     * 解压 Ubuntu rootfs tar.gz 到 files/ubuntu。
     * 纯 Kotlin 实现：不执行 chown（系统禁止非 root 改属主）、不依赖 tar 命令，
     * 所有文件以当前用户写入应用私有目录。
     */
    fun extractRootfs(tarball: File) {
        if (!tarball.exists()) throw RuntimeException("文件不存在")
        val target = File(rootfsDir)
        if (target.exists()) target.deleteRecursively()
        target.mkdirs()
        try {
            extractTarGz(tarball, target)
            // 双保险：确保所有可执行目录里的二进制都有执行位
            ensureExecutable(target)
        } catch (e: Exception) {
            throw RuntimeException("解压失败: ${e.message ?: e.javaClass.simpleName}")
        }
        if (!isRootfsInstalled()) throw RuntimeException("解压完成但未找到 bin/bash，可能不是 Linux rootfs")
    }

    private fun extractTarGz(gz: File, target: File) {
        GZIPInputStream(gz.inputStream().buffered()).use { input ->
            val header = ByteArray(512)
            val dataBuf = ByteArray(64 * 1024)
            var longName: String? = null
            var processed = 0
            while (true) {
                val hdrRead = readFully(input, header)
                if (hdrRead < 512) break // EOF
                if (header.all { it == 0.toByte() }) break // 结束块
                if (!isUstar(header)) throw RuntimeException("不是有效的 tar 格式")
                var name = tarString(header, 0, 100)
                val prefix = tarString(header, 345, 155)
                if (prefix.isNotEmpty()) name = "$prefix/$name"
                val size = parseOctal(header, 124, 12)
                // mode 字段（offset 100, 8 字节八进制）—— 必须保留执行位，否则 rootfs 二进制不可执行
                val mode = (parseOctal(header, 100, 8) and 0x1FF).toInt()
                val type = header[156].toInt().toChar()

                if (longName != null) {
                    name = longName
                    longName = null
                }

                try {
                when (type) {
                    'L' -> {
                        // GNU longname：内容就是下一个条目的名字
                        longName = readEntryText(input, size, dataBuf)
                    }
                    '5' -> {
                        val d = safeFile(target, name)
                        d.mkdirs()
                        applyMode(d, mode)
                        skipBytes(input, size)
                    }
                    '2' -> {
                        // 软链接：linkname 字段（offset 157, 100 bytes）
                        val link = tarString(header, 157, 100)
                        val f = safeFile(target, name)
                        f.parentFile?.mkdirs()
                        if (f.exists()) f.delete()
                        try {
                            android.system.Os.symlink(link, f.absolutePath)
                        } catch (e: Exception) {
                            // 个别 ROM 限制软链则跳过
                        }
                        skipBytes(input, size)
                    }
                    '1' -> {
                        // 硬链接：size 为 0，内容来自 linkname 指向的文件
                        val link = tarString(header, 157, 100)
                        val src = safeFile(target, link)
                        val f = safeFile(target, name)
                        f.parentFile?.mkdirs()
                        if (src.exists()) {
                            src.copyTo(f, overwrite = true)
                        }
                        skipBytes(input, size)
                    }
                    '0', ' ' -> {
                        // 普通文件：写入后按 tar 记录的模式设置权限（保留执行位）
                        val f = safeFile(target, name)
                        f.parentFile?.mkdirs()
                        copyN(input, f, size, dataBuf)
                        applyMode(f, mode)
                    }
                    else -> {
                        skipBytes(input, size)
                    }
                }
                } catch (e: Exception) {
                    writeDiag("条目处理失败 name=$name type=$type err=${e.message}")
                    throw e
                }
                // 对齐到 512
                val pad = (512 - (size % 512)) % 512
                skipBytes(input, pad)
                processed++
            }
            // 完整性校验：usr/bin/bash 与 dpkg 数据库必须存在
            val marker1 = File(target, "usr/bin/bash")
            val marker2 = File(target, "var/lib/dpkg/status")
            if (!marker1.exists() && !File(target, "bin/bash").exists()) {
                writeDiag("解压不完整：usr/bin/bash 缺失（已处理 $processed 条目），rootfs 将重建")
                throw RuntimeException("解压不完整：bash 缺失（已处理 $processed 条目）")
            }
            if (!marker2.exists()) {
                writeDiag("解压不完整：var/lib/dpkg/status 缺失（已处理 $processed 条目）")
            }
            writeDiag("解压完成：$processed 条目")
        }
    }

    /** 权限清扫：递归确保 bin/usr/bin/usr/sbin 下的文件可执行 */
    private fun ensureExecutable(root: File) {
        try {
            for (sub in listOf("bin", "usr/bin", "usr/sbin")) {
                val dir = File(root, sub)
                if (!dir.exists()) continue
                dir.walkTopDown().forEach { f ->
                    try {
                        android.system.Os.chmod(f.absolutePath, 0x1ED) // 0755
                        f.setExecutable(true, false)
                        f.setReadable(true, false)
                        if (f.isDirectory) f.setWritable(true, false)
                    } catch (e: Exception) {
                        writeDiag("清扫 chmod 失败 ${f.absolutePath}: ${e.message}")
                    }
                }
            }
            // /bin 可能是软链到 usr/bin，确保 bash 本体
            for (b in listOf(File(root, "bin/bash"), File(root, "usr/bin/bash"))) {
                if (b.exists()) {
                    try {
                        android.system.Os.chmod(b.absolutePath, 0x1ED)
                    } catch (e: Exception) {
                    }
                }
            }
        } catch (e: Exception) {
        }
    }

    /** 设置文件/目录权限（tar mode 可能为 0，此时给默认值）。
     *  双保险：Os.chmod + File.setExecutable；失败写入诊断日志 */
    private fun applyMode(f: File, mode: Int) {
        try {
            val m = if (mode == 0) {
                if (f.isDirectory) 0x1FF else 0x1A4 // 0777 目录 / 0644 文件
            } else {
                mode
            }
            android.system.Os.chmod(f.absolutePath, m)
            f.setExecutable(true, false)
            f.setReadable(true, false)
            if (f.isDirectory) f.setWritable(true, false)
        } catch (e: Exception) {
            writeDiag("chmod 失败 ${f.absolutePath}: ${e.message}")
        }
    }

    private fun readFully(input: java.io.InputStream, b: ByteArray): Int {
        var off = 0
        while (off < b.size) {
            val r = input.read(b, off, b.size - off)
            if (r < 0) break
            off += r
        }
        return off
    }

    private fun skipBytes(input: java.io.InputStream, n: Long) {
        var remain = n
        val buf = ByteArray(8192)
        while (remain > 0) {
            val r = input.read(buf, 0, minOf(buf.size.toLong(), remain).toInt())
            if (r < 0) break
            remain -= r
        }
    }

    private fun copyN(input: java.io.InputStream, f: File, n: Long, buf: ByteArray) {
        f.outputStream().use { out ->
            var remain = n
            while (remain > 0) {
                val r = input.read(buf, 0, minOf(buf.size.toLong(), remain).toInt())
                if (r < 0) break
                out.write(buf, 0, r)
                remain -= r
            }
        }
    }

    private fun readEntryText(input: java.io.InputStream, n: Long, buf: ByteArray): String {
        val bytes = java.io.ByteArrayOutputStream()
        var remain = n
        while (remain > 0) {
            val r = input.read(buf, 0, minOf(buf.size.toLong(), remain).toInt())
            if (r < 0) break
            bytes.write(buf, 0, r)
            remain -= r
        }
        return bytes.toString(Charsets.UTF_8).trimEnd('\u0000')
    }

    private fun isUstar(h: ByteArray): Boolean {
        val magic = String(h, 257, 6, Charsets.US_ASCII)
        return magic == "ustar\u0000" || magic == "ustar "
    }

    private fun tarString(h: ByteArray, off: Int, len: Int): String =
        String(h, off, len, Charsets.US_ASCII).trimEnd('\u0000', ' ')

    private fun parseOctal(h: ByteArray, off: Int, len: Int): Long {
        var v = 0L
        for (i in off until off + len) {
            val c = h[i].toInt().toChar()
            if (c == '\u0000' || c == ' ') break
            if (c < '0' || c > '7') break
            v = v * 8 + (c - '0')
        }
        return v
    }

    private fun safeFile(target: File, name: String): File {
        val clean = name.trimStart('/')
            .split('/')
            .filter { it.isNotEmpty() && it != "." && it != ".." }
            .joinToString("/")
        val f = File(target, clean)
        val t = target.canonicalFile
        if (!f.canonicalFile.path.startsWith(t.path)) {
            throw RuntimeException("非法路径: $name")
        }
        return f
    }
}
