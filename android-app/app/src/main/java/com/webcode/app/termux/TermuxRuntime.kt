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

    /**
     * 使用国内镜像（阿里云）：archive.ubuntu.com 在国内慢/不稳，导致 apt 下载失败。
     * 重写 /etc/apt/sources.list.d/ubuntu.sources（deb822 格式）。
     * 关键：
     *  - arm64/arm 必须走 ubuntu-ports（mirrors.aliyun.com/ubuntu/ 只有 amd64 索引，
     *    arm64 请求 binary-arm64 全部 404 → apt update 索引为空 → Unable to locate package）
     *  - x86_64/i686 用 mirrors.aliyun.com/ubuntu/
     *  - Suites 从 /etc/os-release 动态读取代号，读不到默认 questing（26.04 LTS）
     */
    fun ensureAptMirror() {
        try {
            if (!isRootfsInstalled()) return
            val f = File(rootfsDir, "etc/apt/sources.list.d/ubuntu.sources")
            f.parentFile?.mkdirs()
            val arch = archName()
            val mirror = if (arch == "aarch64" || arch == "arm") {
                "http://mirrors.aliyun.com/ubuntu-ports/"
            } else {
                "http://mirrors.aliyun.com/ubuntu/"
            }
            var codename = "questing"
            try {
                val osRelease = File(rootfsDir, "etc/os-release").readText()
                val m = Regex("""^VERSION_CODENAME\s*=\s*(.+)$""", RegexOption.MULTILINE).find(osRelease)
                if (m != null && m.groupValues[1].isNotBlank()) codename = m.groupValues[1].trim()
            } catch (e: Exception) {
            }
            val keyring = File(rootfsDir, "usr/share/keyrings/ubuntu-archive-keyring.gpg")
            val signedBy = if (keyring.exists()) {
                "Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg\n"
            } else {
                ""
            }
            val content = "Types: deb\n" +
                "URIs: $mirror\n" +
                "Suites: $codename ${codename}-updates ${codename}-security\n" +
                "Components: main restricted universe multiverse\n" +
                signedBy
            val exists = f.exists()
            if (!exists || !f.readText().contains("ubuntu-ports") || !f.readText().contains(codename)) {
                f.writeText(content)
            }
        } catch (e: Exception) {
        }
    }

    /** 确保 proot 就绪（rootfs 模式唯一依赖） */
    fun ensureProot() {
        installProotComponents()
    }

    /**
     * 确保 rootfs 的 DNS 可用：
     * Android 宿主没有 /etc/resolv.conf（DNS 由 netd 处理），
     * rootfs 自带的 resolv.conf 是空的 → 必须写入公共 DNS 服务器，否则 apt 等全部无法解析。
     * 注意：新版 Ubuntu 的 resolv.conf 是符号链接（指向 systemd stub），rootfs 无 systemd-resolved
     * 在跑，必须先删除链接重建普通文件，否则解析依然失败。
     */
    fun ensureDns() {
        ensureAptMirror()
        // 全量权限修复只跑一次（几千文件 stat 遍历耗时，标记后秒开终端）
        if (!File(AppFilesDir, ".rootfs_writable_fixed").exists()) {
            ensureVarLibWritable()
            ensureRootfsWritable()
            try {
                File(AppFilesDir, ".rootfs_writable_fixed").writeText(
                    System.currentTimeMillis().toString()
                )
            } catch (e: Exception) {
            }
        }
        try {
            if (!isRootfsInstalled()) return
            val f = File(rootfsDir, "etc/resolv.conf")
            f.delete()
            f.parentFile?.mkdirs()
            f.writeText(
                "nameserver 223.5.5.5\n" +   // 阿里 DNS
                "nameserver 114.114.114.114\n" + // 114DNS
                "nameserver 8.8.8.8\n" +
                "nameserver 1.1.1.1\n"
            )
            val hosts = File(rootfsDir, "etc/hosts")
            if (!hosts.exists() || !hosts.readText().contains("127.0.0.1")) {
                hosts.writeText(
                    "127.0.0.1 localhost\n" +
                    "::1 localhost ip6-localhost ip6-loopback\n" +
                    "127.0.1.1 webcode\n"
                )
            }
            // dpkg 备份兜底：Android 上 hard link 受限（proot 已用 --link2symlink），
            // force-unsafe-io 让 dpkg 跳过 status-old 备份，双保险避免 Permission denied
            try {
                val cfgDir = File(rootfsDir, "etc/dpkg/dpkg.cfg.d")
                cfgDir.mkdirs()
                val cfg = File(cfgDir, "99webcode")
                if (!cfg.exists() || !cfg.readText().contains("force-unsafe-io")) {
                    cfg.writeText("force-unsafe-io\n")
                }
            } catch (e: Exception) {
            }
        } catch (e: Exception) {
        }
    }

    /**
     * 兜底 dpkg/apt 工作目录可写：
     * 自制 rootfs 镜像若目录权限过严（如 tar 里 /var/lib/dpkg 为 root-only），
     * dpkg 会报 "error creating new backup file '/var/lib/dpkg/status-old': Permission denied"。
     * 对 var/lib/dpkg、var/lib/apt、var/cache/apt 递归设置为可写，缺失目录补建。
     * 注意：proot -0 模式下 rootfs 内的 chmod 会被模拟不生效，必须在这里用真实 uid 设置。
     */
    fun ensureVarLibWritable() {
        try {
            if (!isRootfsInstalled()) return
            val targets = listOf("var/lib/dpkg", "var/lib/apt", "var/cache/apt")
            for (rel in targets) {
                val dir = File(rootfsDir, rel)
                if (!dir.exists()) dir.mkdirs()
                var failed = 0
                dir.walkTopDown().forEach { p ->
                    try {
                        val mode = if (p.isDirectory) 0x1FF else 0x1A4 // 0777 / 0644
                        android.system.Os.chmod(p.absolutePath, mode)
                        p.setWritable(true, false)
                        p.setReadable(true, false)
                    } catch (e: Exception) {
                        failed++
                        try {
                            // Os.chmod 失败时用外部 chmod 命令后备（不依赖 JNI 绑定）
                            Runtime.getRuntime().exec(arrayOf("chmod", if (p.isDirectory) "0777" else "0644", p.absolutePath)).waitFor()
                        } catch (e2: Exception) {
                        }
                    }
                }
                if (failed > 0) {
                    writeDiag("ensureVarLibWritable: $rel 有 $failed 个条目 chmod 失败")
                }
            }
        } catch (e: Exception) {
            writeDiag("ensureVarLibWritable 异常: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * 准备宿主 dpkg 目录（用于 proot -b 绑定 /var/lib/dpkg）：
     * rootfs 镜像的 dpkg 目录权限可能过严导致 "status-old Permission denied"，
     * 绑定到 App 私有目录后 100% 可写。首次自动迁移 rootfs 现有 dpkg 状态。
     */
    fun ensureHostDpkgDir(context: Context): String {
        val host = File(context.filesDir, "var_lib_dpkg")
        host.mkdirs()
        val root = File(rootfsDir, "var/lib/dpkg")
        if (root.exists() && (host.listFiles()?.isEmpty() != false)) {
            try {
                root.copyRecursively(host, overwrite = true)
            } catch (e: Exception) {
            }
        }
        return host.absolutePath
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
        // rootfs 变了，权限修复标记失效，需要重新全量修复一次
        try {
            File(AppFilesDir, ".rootfs_writable_fixed").delete()
        } catch (e: Exception) {
        }
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
     *  双保险：Os.chmod + File.setExecutable；失败写入诊断日志。
     *  关键：文件也必须可写（镜像里只读文件会让 dpkg 备份/解包 Permission denied） */
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
            // 目录和文件都必须可写（owner 位），否则 dpkg 无法创建备份/硬链接
            f.setWritable(true, false)
        } catch (e: Exception) {
            writeDiag("chmod 失败 ${f.absolutePath}: ${e.message}")
        }
    }

    /**
     * 对已安装的 rootfs 全量补齐写权限（幂等，只修不可写条目）：
     * 老版本解压时文件没设写位，镜像里只读文件导致 dpkg "unable to make backup link ... Permission denied"
     * 与 status-old 备份失败。每次终端创建时调用，开销仅 stat 遍历（几百 ms）。
     */
    fun ensureRootfsWritable() {
        try {
            if (!isRootfsInstalled()) return
            val root = File(rootfsDir)
            var fixed = 0
            root.walkTopDown().forEach { p ->
                try {
                    if (p.isDirectory) {
                        if (!p.canWrite()) {
                            android.system.Os.chmod(p.absolutePath, 0x1FF)
                            p.setWritable(true, false)
                            fixed++
                        }
                    } else {
                        if (!p.canWrite()) {
                            android.system.Os.chmod(p.absolutePath, 0x1A4)
                            p.setWritable(true, false)
                            fixed++
                        }
                    }
                } catch (e: Exception) {
                }
            }
            if (fixed > 0) writeDiag("ensureRootfsWritable: 补齐 $fixed 个不可写条目")
        } catch (e: Exception) {
            writeDiag("ensureRootfsWritable 异常: ${e.message ?: e.javaClass.simpleName}")
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
