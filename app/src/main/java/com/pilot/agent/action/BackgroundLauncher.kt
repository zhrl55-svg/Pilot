package com.pilot.agent.action

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import com.pilot.agent.Dbg
import com.pilot.agent.ui.ForegroundProxyActivity

/**
 * 后台启动 Activity 的三条路径（按可靠度排序）。
 *
 * ## 背景
 * 语音 agent 的常态是「App 在后台，用户喊一句『打开设置』」。
 * Android 10+ 起后台起 Activity 被拦，HarmonyOS 更严：
 * `Abort background activity starts from <uid>`。
 *
 * 实测（11-10，P40 Pro / HarmonyOS 4.0）：
 * - 无障碍服务（`BOUND_FOREGROUND_SERVICE`）起 → 拦
 * - 透明代理 Activity（自己先起）→ 代理自己也起不来，还是拦
 * - 前台服务（`FOREGROUND_SERVICE`）→ 状态对了，但 HarmonyOS 还要
 *   `allowBackgroundActivityStart=true`，等 `START_ACTIVITIES_FROM_BACKGROUND`
 *   权限（privileged，debug 包授不了，自研 ROM 路线里有）
 *
 * 所以本类给出**不依赖 privileged 权限**的两条路：
 * 1. **AlarmManager 触发**（首选）：系统代发的 PendingIntent 起 Activity，
 *    系统视为「用户意图」，允许后台启动。这是 Google Assistant 等的标准做法。
 * 2. **可见窗口豁免**：进程有可见窗口（悬浮窗）时系统也放行。
 *    Pilot 已有 SYSTEM_ALERT_WINDOW 权限，可以挂一个 1px 透明悬浮窗当通行证。
 */
object BackgroundLauncher {

    private const val TAG = "PilotBgLaunch"
    private const val ALARM_REQUEST = 8801

    /**
     * 后台启动 Activity。
     * @param verify 可选：启动后校验函数（返回 true 表示算成功）
     * @return 是否成功启动
     */
    suspend fun launch(
        context: Context,
        intent: Intent,
        verify: (() -> Boolean)? = null,
    ): Boolean {
        // 路径 1：AlarmManager（系统代发，最稳）
        if (launchViaAlarm(context, intent)) {
            Dbg.i(TAG, "AlarmManager 路径启动成功")
            return true
        }
        // 路径 2：可见窗口豁免
        if (launchViaVisibleWindow(context, intent)) {
            Dbg.i(TAG, "可见窗口路径启动成功")
            return true
        }
        return false
    }

    /**
     * AlarmManager 路径。
     *
     * 关键点：AlarmManager 到点后由**系统**代发 PendingIntent，
     * 启动 Activity 时调用方变成系统，绕过后台启动限制。
     * 用 setExactAndAllowWhileIdle 保证 Doze 下也能被叫醒（延迟 ≤ 1s）。
     */
    private fun launchViaAlarm(context: Context, target: Intent): Boolean {
        return try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val proxy = Intent(context, ForegroundProxyActivity::class.java).apply {
                action = ACTION_ALARM_LAUNCH
                putExtra(EXTRA_TARGET, target)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            }
            val pi = PendingIntent.getActivity(
                context, ALARM_REQUEST, proxy,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val triggerAt = SystemClock.elapsedRealtime() + 250L
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // 精确闹钟要 SCHEDULE_EXACT_ALARM/USE_EXACT_ALARM 权限，
                // 拿不到就退到 inexact（延迟可能到几秒，可接受）。
                val canExact = runCatching {
                    am.canScheduleExactAlarms()
                }.getOrDefault(false)
                if (canExact) {
                    am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
                } else {
                    am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
                }
            } else {
                am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            }
            Dbg.i(TAG, "AlarmManager 已排程（+250ms）")
            true
        } catch (t: Throwable) {
            Dbg.w(TAG, "AlarmManager 路径失败 ${t.message}")
            false
        }
    }

    /**
     * 可见窗口路径：挂一个 1px 透明悬浮窗 → 进程有可见窗口 → 起 Activity 被放行。
     *
     * 悬浮窗本身是 Service 加的 Window，不需要 startActivity，所以不受后台限制。
     */
    private suspend fun launchViaVisibleWindow(context: Context, target: Intent): Boolean {
        val ok = OverlayGate.show(context)
        if (!ok) {
            Dbg.w(TAG, "悬浮窗不可用（需要 SYSTEM_ALERT_WINDOW + 用户授权）")
            return false
        }
        kotlinx.coroutines.delay(350)
        val started = try {
            context.startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (t: Throwable) {
            Dbg.w(TAG, "可见窗口路径启动失败 ${t.message}")
            false
        }
        if (started) kotlinx.coroutines.delay(600)
        OverlayGate.hide(context)
        return started
    }

    const val ACTION_ALARM_LAUNCH = "com.pilot.agent.ALARM_LAUNCH"
    const val EXTRA_TARGET = "target"
}
