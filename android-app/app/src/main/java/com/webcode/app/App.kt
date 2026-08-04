package com.webcode.app

import android.app.Application
import android.content.ContentValues
import androidx.appcompat.app.AppCompatDelegate
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class App : Application() {

    override fun onCreate() {
        // 崩溃处理器必须最先安装：任何后续初始化（含资源/主题）崩溃都能留下日志
        installCrashHandler()
        super.onCreate()
        // 应用 UI 一律使用深色，强制夜间模式，避免系统浅色模式下黑字不可见
        try {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        } catch (e: Exception) {
        }
    }

    /** 全局崩溃捕获：写 filesDir/crash.log + 公共下载目录，下次启动可在登录页查看 */
    private fun installCrashHandler() {
        val default = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                PrintWriter(sw).use { throwable.printStackTrace(it) }
                val content = "时间: ${System.currentTimeMillis()}\n线程: ${thread.name}\n" +
                    sw.toString() + "\n\n==========\n"
                writeCrashLog(content)
            } catch (e: Exception) {
            }
            try {
                default?.uncaughtException(thread, throwable)
            } catch (e: Exception) {
            }
        }
    }

    private fun writeCrashLog(content: String) {
        // 1) 应用私有目录（登录页可读）
        try {
            val f = File(filesDir, "crash.log")
            f.parentFile?.mkdirs()
            f.writeText(f.readTextOrEmpty() + content)
        } catch (e: Exception) {
        }

        // 2) 应用专属外部目录（无需任何权限，始终可写）
        try {
            val ext = getExternalFilesDir(null)
            if (ext != null) {
                val f2 = File(ext, "crash.log")
                f2.writeText(f2.readTextOrEmpty() + content)
            }
        } catch (e: Exception) {
        }

        // 3) 公共下载目录（API 29+ 用 MediaStore；API 24-28 用传统路径）
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "WebCode-crash.log")
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS
                    )
                }
                val uri = contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                )
                uri?.let {
                    contentResolver.openOutputStream(it)?.use { out ->
                        out.write(content.toByteArray())
                    }
                }
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "WebCode"
                )
                if (dir.exists() || dir.mkdirs()) {
                    File(dir, "crash.log").appendText(content)
                }
            }
        } catch (e: Exception) {
        }
    }

    private fun File.readTextOrEmpty(): String = try {
        readText()
    } catch (e: Exception) {
        ""
    }
}
