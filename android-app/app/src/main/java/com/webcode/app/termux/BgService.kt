package com.webcode.app.termux

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.webcode.app.R
import com.webcode.app.ui.MainActivity

/**
 * 后台运行服务：
 *  - 前台服务 + 常驻通知（后台运行按钮）
 *  - 启动 1px 像素悬浮窗保活（防止进程被杀）
 *  - 电池优化忽略请求
 */
class BgService : Service() {

    private var overlay: PixelOverlay? = null
    private var running = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())
        PixelOverlay.requestOverlayPermissionIfNeeded(this)
        startWatcher()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_REQUEST_BATTERY -> requestIgnoreBatteryOptimization()
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        overlay?.remove()
        super.onDestroy()
    }

    private fun startWatcher() {
        // 权限已就绪则立即创建 1px 保活悬浮窗
        if (overlay == null) {
            try {
                overlay = PixelOverlay.create(this)
            } catch (e: Exception) {
            }
        }
        Thread {
            while (running) {
                try {
                    Thread.sleep(15_000)
                    if (overlay == null && Settings.canDrawOverlays(this)) {
                        overlay = PixelOverlay.create(this)
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    // 无悬浮窗权限等，忽略
                }
            }
        }.apply { isDaemon = true; start() }
    }

    private fun buildNotification(): Notification {
        createChannel()

        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val battery = PendingIntent.getService(
            this, 1,
            Intent(this, BgService::class.java).setAction(ACTION_REQUEST_BATTERY),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 2,
            Intent(this, BgService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_robot)
            .setContentTitle("WebCode 后台运行中")
            .setContentText("本地直连模式 · 已开启 1px 保活悬浮窗")
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(0, "忽略电池优化", battery)
            .addAction(0, "停止", stop)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "后台运行", NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "WebCode 后台运行保活"
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun requestIgnoreBatteryOptimization() {
        val pm = getSystemService(PowerManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(android.net.Uri.parse("package:$packageName"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) {
                startActivity(
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "webcode_bg"
        const val NOTIF_ID = 3456
        const val ACTION_REQUEST_BATTERY = "com.webcode.app.REQUEST_BATTERY"
        const val ACTION_STOP = "com.webcode.app.STOP"

        fun start(context: Context) {
            val intent = Intent(context, BgService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BgService::class.java))
        }

        fun requestBattery(context: Context) {
            val pm = context.getSystemService(PowerManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !pm.isIgnoringBatteryOptimizations(context.packageName)) {
                try {
                    context.startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .setData(android.net.Uri.parse("package:${context.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (e: Exception) {
                    context.startActivity(
                        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
        }

        fun isBatteryOptimizationIgnored(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                context.getSystemService(PowerManager::class.java)
                    .isIgnoringBatteryOptimizations(context.packageName)
    }
}
