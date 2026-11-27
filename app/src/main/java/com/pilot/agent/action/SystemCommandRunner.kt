package com.pilot.agent.action

/**
 * B 类系统命令的实际执行（m-20261127-104 / m-20261127-105 共用）。
 *
 * 从 AgentLoop 抽出来，是为了让「无障碍未连接」时也能直接执行这些不需要屏幕的命令，
 * 不必等 AgentLoop 起来。返回值是三态回执所需的素材，文案仍在 CommandSemantics。
 */
sealed interface SystemRunOutcome {
    data class Done(val message: String) : SystemRunOutcome

    /** 需要权限/没有能力：调用方负责问「要不要带路」。 */
    data class NeedsGuide(
        val message: String,
        val target: CommandTarget,
        val reason: String,
    ) : SystemRunOutcome
}

object SystemCommandRunner {

    /** @return null = 这个目标不归这里执行（导航或未知）。 */
    fun run(ops: SystemOps, cmd: SystemCommand, goal: String): SystemRunOutcome? = when (cmd.target) {
        CommandTarget.BRIGHTNESS -> brightness(ops, goal)
        CommandTarget.VOLUME -> volume(ops, goal)
        CommandTarget.DND -> dnd(ops, cmd.on == true)
        else -> null
    }

    private fun brightness(ops: SystemOps, goal: String): SystemRunOutcome? {
        val change = SystemOps.parseLevelChange(goal) ?: return null
        val r = when (change) {
            is LevelChange.Delta -> ops.adjustBrightness(change.percent)
            is LevelChange.Absolute -> ops.setBrightness(change.percent)
        }
        return when (r) {
            null -> SystemRunOutcome.Done("调亮度失败了，系统没有接受这次修改。")
            SystemOps.NEED_WRITE_SETTINGS -> needsGuide(
                CommandTarget.BRIGHTNESS,
                null,
                "需要「修改系统设置」权限才能改亮度",
            )

            else -> SystemRunOutcome.Done(r)
        }
    }

    private fun volume(ops: SystemOps, goal: String): SystemRunOutcome? {
        val change = SystemOps.parseLevelChange(goal)
        val r = when {
            change is LevelChange.Delta -> ops.adjustVolume(change.percent)
            change is LevelChange.Absolute -> ops.setVolume(change.percent)
            goal.contains("静音") -> ops.setVolume(0)
            else -> return null
        }
        return SystemRunOutcome.Done(r ?: "调音量失败了，系统没有接受这次修改。")
    }

    private fun dnd(ops: SystemOps, on: Boolean): SystemRunOutcome {
        val r = ops.setDnd(on)
        return when (r) {
            null -> SystemRunOutcome.Done("没改成：系统没有接受勿扰切换。")
            SystemOps.NEED_DND_ACCESS ->
                needsGuide(CommandTarget.DND, on, "需要通知策略访问权限才能改勿扰")
            else -> SystemRunOutcome.Done(r)
        }
    }

    private fun needsGuide(target: CommandTarget, on: Boolean?, reason: String): SystemRunOutcome.NeedsGuide {
        val cmd = SystemCommand(CommandKind.IMPOSSIBLE, target, on, reason)
        return SystemRunOutcome.NeedsGuide(CommandSemantics.needsUserText(cmd), target, reason)
    }
}
