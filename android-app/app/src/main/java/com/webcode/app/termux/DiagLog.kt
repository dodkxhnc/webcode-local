package com.webcode.app.termux

import android.content.Context
import java.io.File

/**
 * 集中式诊断日志：所有错误（含网络/API/运行异常）记录到
 * Android/data/com.webcode.app/files/errors.log（文件管理器可访问，可上传）
 */
object DiagLog {

    @Volatile
    private var dir: File? = null

    fun init(context: Context) {
        if (dir == null) {
            dir = context.getExternalFilesDir(null)
        }
    }

    fun log(context: Context?, tag: String, msg: String) {
        try {
            if (context != null) init(context)
            val f = File(dir, "errors.log")
            f.parentFile?.mkdirs()
            val line = "[${System.currentTimeMillis()}] [$tag] $msg\n"
            f.appendText(line)
        } catch (e: Exception) {
        }
    }
}
