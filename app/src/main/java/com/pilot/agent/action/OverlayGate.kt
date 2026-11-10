package com.pilot.agent.action

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.pilot.agent.Dbg

/**
 * 「可见窗口豁免」闸门：挂一个 1px 透明悬浮窗。
 *
 * ## 原理
 * Android 判定后台启动时看进程**有没有可见窗口**。有可见窗口的进程
 * 起 Activity 会被放行（这是给输入法、悬浮球这类工具的口子）。
 * Pilot 已有 SYSTEM_ALERT_WINDOW 权限，可以借这个口子当「后台启动通行证」。
 *
 * ## 为什么不用它当主路径
 * 悬浮窗要用户手动去设置里开「显示在其他应用上层」，一步摩擦。
 * 所以主路径是 AlarmManager（系统代发，零摩擦），这个是兜底。
 *
 * 用完立即 removeView——只是瞬时提权，不是常驻悬浮窗。
 */
object OverlayGate {

    private const val TAG = "PilotOverlay"
    private var windowManager: WindowManager? = null
    private var view: View? = null

    /** 系统是否允许本 App 画悬浮窗（用户没开就返回 false，别去跳设置页打扰用户）。 */
    fun canDraw(context: Context): Boolean = try {
        Settings.canDrawOverlays(context)
    } catch (_: Throwable) {
        false
    }

    fun show(context: Context): Boolean {
        if (!canDraw(context)) return false
        if (view != null) return true
        return try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val v = View(context)
            val params = WindowManager.LayoutParams(
                1, 1,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.TOP or Gravity.START }

            wm.addView(v, params)
            windowManager = wm
            view = v
            Dbg.i(TAG, "可见窗口闸门已开")
            true
        } catch (t: Throwable) {
            Dbg.w(TAG, "闸门开启失败 ${t.message}")
            false
        }
    }

    fun hide(context: Context) {
        val v = view ?: return
        val wm = windowManager ?: return
        view = null
        windowManager = null
        try {
            wm.removeView(v)
            Dbg.i(TAG, "可见窗口闸门已关")
        } catch (t: Throwable) {
            Dbg.w(TAG, "闸门关闭失败 ${t.message}")
        }
    }
}
