package com.pilot.agent.assistant

import android.app.KeyguardManager
import android.content.Context

/**
 * 隐私闸门（本地判定，不联网）。
 *
 * - 锁屏时不读出个人数据（通知、记忆、提醒/待办清单、文件内容、任务结果）。
 * - 个人数据只走本机 TTS（见 [com.pilot.agent.tool.ToolResult.privateSpeech]）。
 *
 * 锁屏判定：voice 任务若提供统一的锁屏 API，替换 [isDeviceLocked] 的实现即可；
 * 当前直接用 KeyguardManager（含滑动锁，偏保守）。
 */
object PrivacyGate {

    const val LOCKED_PERSONAL_REPLY = "手机还锁着，个人内容我先不读出来。解锁后再问我一次。"
    const val LOCKED_RESULT_REPLY = "任务有结果了，内容可能涉及隐私，解锁手机后在 Pilot 里查看。"
    const val LOCKED_CONFIRM_REPLY = "手机还锁着，这个操作需要你解锁后在屏幕上确认。"

    /**
     * 读取个人数据的本地工具名。锁屏时拒绝执行；结果只用本机 TTS。
     */
    val PERSONAL_READ_TOOLS: Set<String> = setOf(
        "memory_list",
        "memory_search",
        "list_reminders",
        "list_todos",
        "read_notifications",
        "read_file",
        "list_files",
        "list_scheduled_tasks",
    )

    fun isDeviceLocked(context: Context): Boolean = try {
        val km = context.applicationContext.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        km?.isKeyguardLocked == true
    } catch (t: Throwable) {
        false
    }

    fun isPersonalRead(toolName: String): Boolean = toolName in PERSONAL_READ_TOOLS
}
