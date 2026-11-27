package com.pilot.agent.action

import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * 系统命令的 A/B/C 语义（m-20261127-104）。
 *
 * 用户说的命令要的是**结果**，不是「打开一个设置页」。所以先给一句话定性：
 * - [CommandKind.NAVIGATE]（A）：用户要的就是到那儿——「打开蓝牙设置」「看看电池」；
 * - [CommandKind.RESULT]（B）：要改状态——「亮度调到 30%」「关掉勿扰」，必须走能改状态的路径；
 * - [CommandKind.IMPOSSIBLE]（C）：系统没有公开 API 或没有权限——如实说改不了 + 一句原因，
 *   **问用户要不要带路，用户点头之前不许跳页面**。
 *
 * 这里只做纯文本判定，不执行任何动作，便于单测。
 */
enum class CommandKind { NAVIGATE, RESULT, IMPOSSIBLE }

enum class CommandTarget(val label: String) {
    BRIGHTNESS("亮度"),
    VOLUME("音量"),
    DND("勿扰"),
    BLUETOOTH("蓝牙"),
    WIFI("WLAN"),
    AIRPLANE("飞行模式"),
    BATTERY_SAVER("省电模式"),
}

data class SystemCommand(
    val kind: CommandKind,
    val target: CommandTarget,
    /** 结果型的方向；null = 未说明（亮度/音量另有数值解析）。 */
    val on: Boolean? = null,
    /** C 类的具体原因。 */
    val reason: String? = null,
)

object CommandSemantics {

    private val TARGETS: List<Pair<CommandTarget, List<String>>> = listOf(
        CommandTarget.BRIGHTNESS to listOf("亮度", "屏幕亮"),
        CommandTarget.VOLUME to listOf("音量", "声音", "静音"),
        CommandTarget.DND to listOf("勿扰", "免打扰"),
        CommandTarget.BLUETOOTH to listOf("蓝牙", "bluetooth"),
        CommandTarget.WIFI to listOf("wifi", "wi-fi", "无线网", "wlan"),
        CommandTarget.AIRPLANE to listOf("飞行模式"),
        CommandTarget.BATTERY_SAVER to listOf("省电", "节电", "电池 saver"),
    )

    /** 「要的是结果」的措辞。出现任意一个，就不允许目录把它当导航。 */
    private val RESULT_PHRASES = listOf(
        "调到", "调至", "调低", "调高", "调大", "调小", "设成", "设为", "改成", "改为",
        "亮一点", "暗一点", "亮些", "暗些", "大一点", "小一点", "大点", "小点",
        "关掉", "关闭", "关了", "关一下", "开启", "开一下", "启用",
        "静音", "勿扰", "省电", "切换", "切到",
    )

    /** 纯导航措辞：用户明确只是想看/去那个页面。 */
    private val NAV_PHRASES = listOf(
        "看看", "看一下", "查看", "浏览", "管理", "在哪", "在哪儿",
        "设置页", "设置页面", "设置界面", "设置里", "页面", "界面", "详情",
    )

    private val OFF_WORDS = listOf("关掉", "关闭", "关了", "关一下", "取消", "停用", "禁用", "别")
    private val ON_WORDS = listOf("打开", "开启", "开一下", "启用", "开")

    /** 结果型/不可行时禁止走「直达设置页」。ActionCatalog 用它兜底。 */
    fun blocksNavigation(goal: String): Boolean =
        classify(goal)?.kind.let { it != null && it != CommandKind.NAVIGATE }

    private val QUESTION_CUES = listOf("怎么", "如何", "为什么", "咋", "吗", "？", "?")

    fun classify(goal: String): SystemCommand? {
        val g = goal.lowercase()
        if (g.isBlank()) return null
        // 疑问句是在问怎么做，不是在让助手动手；交给正常问答链路。
        if (QUESTION_CUES.any { g.contains(it) }) return null
        val target = TARGETS.firstOrNull { (_, words) -> words.any { g.contains(it) } }?.first ?: return null

        val off = OFF_WORDS.any { g.contains(it) }
        val on = ON_WORDS.any { g.contains(it) }
        val resultPhrase = RESULT_PHRASES.any { g.contains(it) }
        val navCue = g.contains("在哪") || g.contains("在哪儿") || NAV_PHRASES.any { g.contains(it) }
        val settingsWord = g.contains("设置")

        // 纯导航：「打开蓝牙设置」「蓝牙在哪」「看看蓝牙」。
        if (!resultPhrase && !off && (navCue || settingsWord)) {
            return SystemCommand(CommandKind.NAVIGATE, target)
        }
        // 「打开蓝牙设置」：on 词 + 「设置」= 去页面，不是拨开关。
        if (settingsWord && !off && !resultPhrase) {
            return SystemCommand(CommandKind.NAVIGATE, target)
        }
        if (!resultPhrase && !off && !on) return null

        val direction = when {
            off -> false
            on -> true
            else -> null
        }

        return when (target) {
            CommandTarget.BRIGHTNESS, CommandTarget.VOLUME, CommandTarget.DND ->
                SystemCommand(CommandKind.RESULT, target, direction)
            CommandTarget.BLUETOOTH ->
                impossible(target, direction, "Android 13 起蓝牙开关没有公开 API，第三方应用拿不到开关权限")
            CommandTarget.WIFI ->
                impossible(target, direction, "Android 10 起关闭 Wi-Fi 没有公开 API，第三方应用改不了")
            CommandTarget.AIRPLANE ->
                impossible(target, direction, "飞行模式只能由系统界面切换，第三方应用没有这个权限")
            CommandTarget.BATTERY_SAVER ->
                impossible(target, direction, "省电模式从 Android 9 起需要系统签名（DEVICE_POWER）才能直接开关")
        }
    }

    private fun impossible(target: CommandTarget, on: Boolean?, reason: String) =
        SystemCommand(CommandKind.IMPOSSIBLE, target, on, reason)

    /** C 类回执：先说做不到 + 原因，再问要不要带路。 */
    fun needsUserText(cmd: SystemCommand): String {
        val verb = when (cmd.on) {
            true -> "打开${cmd.target.label}"
            false -> "关闭${cmd.target.label}"
            null -> "调整${cmd.target.label}"
        }
        val reason = cmd.reason ?: "这个能力受限"
        return "我现在没办法直接$verb：$reason。要我带你到开关页自己拨一下吗？"
    }

    /** B 类回执：改成功后的统一说法（附回读值）。 */
    fun okText(target: CommandTarget, detail: String): String = when (target) {
        CommandTarget.BRIGHTNESS -> "已调好亮度：$detail"
        CommandTarget.VOLUME -> "已调好音量：$detail"
        CommandTarget.DND -> "勿扰状态已更新：$detail"
        else -> detail
    }

    fun guideIntent(target: CommandTarget): Intent = when (target) {
        CommandTarget.BRIGHTNESS ->
            Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).setData(
                android.net.Uri.parse("package:${com.pilot.agent.PilotApp.instance.packageName}"),
            )
        CommandTarget.DND -> Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
        CommandTarget.BLUETOOTH -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        CommandTarget.WIFI -> Intent(Settings.ACTION_WIFI_SETTINGS)
        CommandTarget.AIRPLANE -> Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
        CommandTarget.BATTERY_SAVER -> Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
        CommandTarget.VOLUME -> Intent(Settings.ACTION_SOUND_SETTINGS)
    }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** 用户对「要我带你吗」的肯定回答。只认短句，避免把正常指令吞掉。 */
    fun isAffirmative(text: String): Boolean {
        val t = text.trim().replace(Regex("""[\s，,。.！!？?、~～…]"""), "")
        if (t.isEmpty() || t.length > 8) return false
        val yes = listOf("好", "好的", "要", "去", "去吧", "带我去", "打开", "开吧", "可以", "行", "嗯", "是的", "对")
        return yes.any { t == it || t == it + "吧" || t == it + "啊" } || t.contains("带我去")
    }

    fun guideOpenedText(target: CommandTarget, outcome: LaunchOutcome): String = when (outcome) {
        is LaunchOutcome.Ok -> "已打开${target.label}设置页，你自己拨一下开关就行。"
        is LaunchOutcome.Blocked -> "也没能打开${target.label}设置页（${outcome.reason.take(40)}）。你可以在系统设置里找到它。"
        is LaunchOutcome.Failed -> "这台设备上没有找到${target.label}设置页。"
        LaunchOutcome.Unknown -> "已请求打开${target.label}设置页，但没能确认它到了前台（结果未知），请看一眼手机。"
    }
}
