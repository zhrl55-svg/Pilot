package com.pilot.agent.action

import com.pilot.agent.query.QueryEngine

/**
 * 命令的前置条件（m-20261127-105）。
 *
 * 运行时不能拿「无障碍是否就绪」当全局闸门：建日程、查行情、调音量、加提醒、
 * 放音乐、问设备状态都不需要读屏。只有真要读屏/点屏的动作才需要操作权限。
 *
 * 判定从**已知的端侧能力**出发，认不出来才按需要屏幕处理（保守但不误伤已知能力）。
 * 纯函数，便于单测。
 */
enum class ScreenRequirement {
    /** 不需要无障碍：直接走本地能力。 */
    NONE,

    /** 需要读屏/点屏：没有操作权限时必须先问用户是否带路。 */
    SCREEN,
}

object ScreenRequirements {

    private val MUSIC_WORDS = listOf(
        "播放", "放首", "放一下", "来首", "来一首", "放歌", "听歌", "暂停", "继续播放",
        "下一首", "上一首", "停止播放", "换一首", "随机播放", "单曲循环",
    )

    fun requirement(goal: String): ScreenRequirement {
        val g = goal.trim()
        if (g.isEmpty()) return ScreenRequirement.SCREEN

        // 本地直办：日程 / 提醒 / 待办 / 状态型系统命令 / 音乐 / 内置查询。
        if (CalendarTool.handles(g) || CalendarTool.handlesCancel(g)) return ScreenRequirement.NONE
        if (ReminderManager.handles(g)) return ScreenRequirement.NONE
        if (PersonalTodoStore.handles(g)) return ScreenRequirement.NONE
        // B 类直接执行、C 类只需如实拒绝/带路，都不读屏；只有 NAVIGATE（打开某个设置页/
        // App 内页面）按需要屏幕处理（验收要求：打开设置里的电池页要先问权限）。
        val kind = CommandSemantics.classify(g)?.kind
        if (kind == CommandKind.RESULT || kind == CommandKind.IMPOSSIBLE) return ScreenRequirement.NONE
        if (isMusicCommand(g)) return ScreenRequirement.NONE
        if (QueryEngine.canHandle(g)) return ScreenRequirement.NONE

        return ScreenRequirement.SCREEN
    }

    /** 播放/暂停/切歌这类纯媒体控制，不需要读屏。 */
    fun isMusicCommand(goal: String): Boolean =
        MUSIC_WORDS.any { goal.contains(it) } && !goal.contains("打开")

    fun needsScreen(goal: String): Boolean = requirement(goal) == ScreenRequirement.SCREEN
}
