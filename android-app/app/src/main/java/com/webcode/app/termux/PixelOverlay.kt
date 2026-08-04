package com.webcode.app.termux

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * 1x1 像素悬浮窗 —— 后台保活。
 * 在部分 ROM 上，前台进程持有悬浮窗可显著降低被系统杀的几率。
 */
class PixelOverlay private constructor(
    private val context: Context,
    private val wm: WindowManager,
    private val view: View,
    private val params: WindowManager.LayoutParams
) {
    fun show() {
        if (!Settings.canDrawOverlays(context)) return
        try {
            wm.addView(view, params)
        } catch (e: Exception) {
        }
    }

    fun remove() {
        try {
            wm.removeView(view)
        } catch (e: Exception) {
        }
    }

    companion object {
        fun canDraw(context: Context): Boolean =
            Settings.canDrawOverlays(context) || Build.VERSION.SDK_INT < Build.VERSION_CODES.M

        fun requestOverlayPermissionIfNeeded(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                try {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (e: Exception) {
                }
            }
        }

        fun create(context: Context): PixelOverlay? {
            if (!canDraw(context)) return null
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val view = View(context)
            view.setBackgroundColor(0x01_000000)
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            val params = WindowManager.LayoutParams(
                1, 1,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP or Gravity.START
            params.x = 0
            params.y = 0
            val overlay = PixelOverlay(context, wm, view, params)
            overlay.show()
            return overlay
        }
    }
}
