package com.pilot.navigation

import android.content.Context
import android.content.Intent

/**
 * 导航模块 → 主进程的指令通道。
 *
 * ## 为什么不用广播
 * 华为 ROM 的广播队列会卡死（实测 `am broadcast` 的
 * `dispatchClockTime=1970-01-01`，永远不派发）。
 * 所以跨进程用 **`startActivity` 带 extra**，走 Activity 启动路径，稳定。
 *
 * 主进程侧在 MainActivity / TriggerActivity 里处理这些 extra。
 */
object AgentBridge {

    private const val TAG = "PilotNavBridge"

    /** 让主进程停掉正在播的 TTS。 */
    fun stopSpeaking(context: Context) {
        runCatching {
            val i = Intent().apply {
                setClassName(context.packageName, "com.pilot.agent.ui.TriggerActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("stop_speaking", true)
            }
            context.startActivity(i)
            NLog.i(TAG, "通知主进程停止播报")
        }.onFailure { NLog.w(TAG, "通知停播报失败 ${it.message}") }
    }
}
