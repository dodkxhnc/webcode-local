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

    /** 安装 bootstrap（下载 ~60MB + 解压 + SYMLINKS/权限处理） */
    fun installBootstrap(context: Context, log: (String) -> Unit) {
        TermuxRuntime.init(context)
        if (TermuxRuntime.isBootstrapInstalled()) {
            log("Linux 运行时已安装，跳过")
            return
        }
        log("下载 bootstrap (${TermuxRuntime.archName()})…")
        val zip = TermuxRuntime.downloadBootstrap { done, total ->
            if (total > 0) {
                log("下载中 ${done / 1024 / 1024}MB / ${total / 1024 / 1024}MB")
            }
        }
        log("解压安装…")
        TermuxRuntime.installBootstrap(zip)
        log("运行时安装完成：bash 位于 ${TermuxRuntime.binDir}/bash")
    }

    fun isInstalled(): Boolean = TermuxRuntime.isBootstrapInstalled()

    fun isInstalled(context: Context): Boolean {
        TermuxRuntime.init(context)
        return TermuxRuntime.isBootstrapInstalled()
    }

    fun bashPath(): String = TermuxRuntime.binDir + "/bash"

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
        if (!TermuxRuntime.isBootstrapInstalled()) {
            return "Linux 运行时未安装，请先在设置页安装（约 60MB）"
        }
        return TermuxRuntime.run(command, timeoutMs, cwd)
    }

    fun shellVersion(): String = try {
        TermuxRuntime.run("echo bash $(${TermuxRuntime.binDir}/bash --version | head -1 | cut -d' ' -f2) 2>&1").trim()
    } catch (e: Exception) {
        ""
    }
}
