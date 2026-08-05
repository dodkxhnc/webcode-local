package com.webcode.app.local

import android.content.Context
import android.net.Uri
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 版本更新检查：从仓库 version.json 拉取最新版本信息，与本地 BuildConfig.VERSION_CODE 对比。
 * version.json 由发布流程维护（仓库根目录）。
 */
object UpdateChecker {

    /** 多源回退：raw.githubusercontent 国内不稳，依次尝试镜像 */
    private val VERSION_URLS = listOf(
        "https://raw.githubusercontent.com/dodkxhnc/webcode-local/main/version.json",
        "https://ghfast.top/https://raw.githubusercontent.com/dodkxhnc/webcode-local/main/version.json",
        "https://ghproxy.net/https://raw.githubusercontent.com/dodkxhnc/webcode-local/main/version.json",
        "https://cdn.jsdelivr.net/gh/dodkxhnc/webcode-local@main/version.json"
    )

    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val notes: String
    )

    /** 后台线程拉取；失败或无需更新返回 null */
    fun checkLatest(onResult: (UpdateInfo?) -> Unit) {
        Thread {
            var result: UpdateInfo? = null
            for (url in VERSION_URLS) {
                try {
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.connectTimeout = 6000
                    conn.readTimeout = 6000
                    conn.setRequestProperty("Accept", "application/json")
                    if (conn.responseCode != 200) {
                        conn.disconnect()
                        continue
                    }
                    val body = conn.inputStream.bufferedReader().readText()
                    conn.disconnect()
                    val j = JSONObject(body)
                    result = UpdateInfo(
                        versionCode = j.optInt("versionCode", 0),
                        versionName = j.optString("versionName", ""),
                        apkUrl = j.optString("apkUrl", ""),
                        notes = j.optString("notes", "")
                    )
                    if (result != null) break
                } catch (e: Exception) {
                }
            }
            onResult(result)
        }.start()
    }

    /** 下载 APK 到缓存目录并返回文件（供 FileProvider 安装） */
    fun downloadApk(context: Context, url: String, onProgress: (Long, Long) -> Unit = { _, _ -> }): File? {
        return try {
            val dir = File(context.cacheDir, "update")
            dir.mkdirs()
            val target = File(dir, "webcode-update.apk")
            if (target.exists()) target.delete()
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.instanceFollowRedirects = true
            val total = conn.contentLength.toLong()
            conn.inputStream.use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
            target
        } catch (e: Exception) {
            null
        }
    }

    /** 触发系统安装器（需要 FileProvider 授权） */
    fun install(context: Context, apk: File): Boolean {
        return try {
            val uri: Uri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "com.webcode.app.fileprovider",
                apk
            )
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
            intent.setDataAndType(uri, "application/vnd.android.package-archive")
            intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 完整下载流程（带进度）：进度对话框 → 下载 → 完成对话框（立即安装 / 稍后安装）。
     * 立即安装前检查"安装未知应用"权限；稍后安装保存到系统下载目录。
     */
    fun downloadWithDialog(activity: android.app.Activity, url: String) {
        val pd = android.app.ProgressDialog(activity)
        pd.setTitle("下载更新")
        pd.setMessage("准备下载…")
        pd.setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL)
        pd.setCancelable(false)
        pd.show()
        Thread {
            val file = downloadApk(activity, url) { done, total ->
                activity.runOnUiThread {
                    try {
                        if (total > 0) {
                            pd.max = total.toInt()
                            pd.setProgress(done.toInt())
                        } else {
                            pd.setProgress(0)
                        }
                        pd.setMessage("下载中 ${done / 1024 / 1024}MB" +
                            if (total > 0) "/${total / 1024 / 1024}MB" else "")
                    } catch (e: Exception) {
                    }
                }
            }
            activity.runOnUiThread {
                try {
                    pd.dismiss()
                } catch (e: Exception) {
                }
                if (file == null) {
                    android.app.AlertDialog.Builder(activity)
                        .setTitle("下载失败")
                        .setMessage("自动下载失败，可能是网络原因。你可以通过浏览器手动下载最新版本：")
                        .setPositiveButton("前往下载") { _, _ ->
                            try {
                                activity.startActivity(
                                    android.content.Intent(
                                        android.content.Intent.ACTION_VIEW,
                                        Uri.parse(url)
                                    )
                                )
                            } catch (e: Exception) {
                            }
                        }
                        .setNegativeButton("取消", null)
                        .show()
                    return@runOnUiThread
                }
                android.app.AlertDialog.Builder(activity)
                    .setTitle("下载完成")
                    .setMessage("新版本 APK 已下载完成（${file.length() / 1024 / 1024}MB）")
                    .setPositiveButton("立即安装") { _, _ ->
                        if (Build.VERSION.SDK_INT >= 26 &&
                            !activity.packageManager.canRequestPackageInstalls()
                        ) {
                            // 引导开启"安装未知应用"权限
                            try {
                                val intent = android.content.Intent(
                                    android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:${activity.packageName}")
                                )
                                activity.startActivity(intent)
                            } catch (e: Exception) {
                            }
                            saveToDownloads(activity, file)
                            android.widget.Toast.makeText(
                                activity,
                                "请允许安装未知应用后，从下载目录重新安装",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        } else {
                            install(activity, file)
                        }
                    }
                    .setNegativeButton("稍后安装") { _, _ ->
                        saveToDownloads(activity, file)
                        android.widget.Toast.makeText(
                            activity,
                            "APK 已保存到下载目录，可稍后手动安装",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                    .show()
            }
        }.start()
    }

    /** 保存 APK 到系统下载目录（API 29+ MediaStore；26-28 外部私有目录） */
    private fun saveToDownloads(context: Context, file: File) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "WebCode-Update.apk")
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/vnd.android.package-archive")
                }
                val uri = context.contentResolver.insert(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                ) ?: return
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                }
            } else {
                val dest = File(context.getExternalFilesDir(null), "WebCode-Update.apk")
                file.copyTo(dest, overwrite = true)
            }
        } catch (e: Exception) {
        }
    }
}
