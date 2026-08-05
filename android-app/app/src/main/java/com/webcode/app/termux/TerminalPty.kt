package com.webcode.app.termux

/**
 * PTY 原生辅助（NDK 编译的极小 C 库）：
 * posix_openpt/grantpt/unlockpt/ptsname + read/write/ioctl(TIOCSWINSZ)
 */
object TerminalPty {

    init {
        System.loadLibrary("webcodepty")
    }

    /** 创建 PTY：返回 master fd，slave 路径写入 nameOut */
    @JvmStatic external fun ptyOpen(nameOut: ByteArray): Int

    /** 设置窗口尺寸 */
    @JvmStatic external fun ptySetSize(fd: Int, rows: Int, cols: Int): Int

    /** 从 master 读取 */
    @JvmStatic external fun ptyRead(fd: Int, buf: ByteArray, off: Int, len: Int): Int

    /** 向 master 写入 */
    @JvmStatic external fun ptyWrite(fd: Int, buf: ByteArray, off: Int, len: Int): Int

    /** 关闭 */
    @JvmStatic external fun ptyClose(fd: Int)
}
