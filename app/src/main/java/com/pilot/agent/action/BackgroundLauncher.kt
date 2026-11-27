package com.pilot.agent.action

import android.content.Context
import android.content.Intent
import com.pilot.agent.Dbg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 后台启动 Activity 的降级路径。
 *
 * ## 背景
 * 语音 agent 的常态是「App 在后台，用户喊一句『打开设置』」。
 * Android 10+ 起后台起 Activity 被拦，HarmonyOS 更严：
 * `Abort background activity starts from <uid>`。
 *
 * ## 2026-11-27 调整（审核 D-02）
 * - 旧版 AlarmManager 路径「已排程」就返回 true（Doze 下可能延迟数分钟，且 AOSP 的 BAL
 *   豁免并不包含闹钟触发的 PendingIntent），属于谎报成功，**已删除**。
 * - 本类不再自己判定成功：挂上可见窗口豁免（[OverlayGate]）后，启动与回查统一交给
 *   [VerifiedLauncher]，返回 [LaunchOutcome]。
 * - 由 [DeviceActions] 在「前台确实没切过去（Blocked）」时调用；Unknown 时不要调用，
 *   避免重复启动（拨号/支付类 Intent 重复启动有副作用）。
 *
 * [ACTION_ALARM_LAUNCH] / [EXTRA_TARGET] 仍被 ForegroundProxyActivity 读取，保留。
 */
object BackgroundLauncher {

    private const val TAG = "PilotBgLaunch"

    /**
     * 借「可见窗口豁免」再启动一次，并回查结果。
     *
     * @return 没有悬浮窗权限 / 悬浮窗挂不上时返回 [LaunchOutcome.Blocked]，不做任何启动。
     */
    suspend fun launch(
        context: Context,
        intent: Intent,
        expectPackage: String?,
        timeoutMs: Long = 4000,
    ): LaunchOutcome {
        if (!OverlayGate.canDraw(context)) {
            return LaunchOutcome.Blocked("没有「显示在其他应用上层」权限，无法借悬浮窗放行后台启动")
        }
        val shown = withContext(Dispatchers.Main) { OverlayGate.show(context) }
        if (!shown) {
            Dbg.w(TAG, "悬浮窗闸门开启失败")
            return LaunchOutcome.Blocked("悬浮窗闸门开启失败")
        }
        try {
            delay(350) // 等窗口 attach，进程状态才会被系统视为「有可见窗口」
            val outcome = VerifiedLauncher.launch(context, intent, expectPackage, timeoutMs)
            Dbg.i(TAG, "可见窗口路径启动结果：$outcome")
            return outcome
        } finally {
            // 取消时也必须把悬浮窗摘掉
            withContext(NonCancellable + Dispatchers.Main) { OverlayGate.hide(context) }
        }
    }

    const val ACTION_ALARM_LAUNCH = "com.pilot.agent.ALARM_LAUNCH"
    const val EXTRA_TARGET = "target"
}
