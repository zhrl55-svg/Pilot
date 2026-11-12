package com.pilot.agent.agent

/**
 * 规划器链：按成本从低到高排，前一个说"不会"就交给下一个。
 *
 * 顺序不是随便定的：
 * 1. **规则**永远先试。成本 0、延迟 0，覆盖不了会立刻返回 unable，不浪费任何时间。
 * 2. **端侧小模型**只在"简单短句"时插到云端前面——0.6B 干不了长链推理，
 *    让它去规划跨应用任务纯属浪费 1.5 秒。
 * 3. **云端**是复杂任务的主力。
 * 4. 没网/没配 key 时，端侧小模型兜在最后，至少不会变成哑巴。
 *
 * 这个顺序还解决了一个真问题：以前云端不可用会直接抛异常把任务打断，
 * 现在异常被吞在这一层（[Planner.Unavailable]），链继续往下走。
 */
class PlannerRouter(
    private val settingsProvider: () -> AgentSettings,
    private val cloud: CloudPlanner,
    private val onDevice: HeuristicPlanner,
    private val local: LocalPlanner,
) {

    fun chain(goal: String, stepIndex: Int): List<Planner> {
        val s = settingsProvider()
        val simple = isSingleStep(goal)
        return buildList {
            if (s.mode == AssistantMode.AGENT) {
                // Fast rules still get first refusal for atomic system actions.
                add(onDevice)
                if (s.cloudReady) add(cloud)
                if (s.enableLocalModel) add(local)
                return@buildList
            }
            add(onDevice)
            if (simple && s.enableLocalModel) add(local)
            if (s.cloudReady) add(cloud)
            if (!simple && s.enableLocalModel) add(local)
        }
    }

    fun cloud(): Planner = cloud

    fun onDevice(): Planner = onDevice

    fun local(): Planner = local

    /** 单步短句才值得交给端侧小模型；长句/多动词直接上云 */
    private fun isSingleStep(goal: String): Boolean {
        val g = goal.trim()
        if (g.length > 14) return false
        val multiStepHints = listOf("然后", "接着", "并且", "之后", "顺便", "再帮", "依次")
        if (multiStepHints.any { g.contains(it) }) return false
        val verbCount = listOf("打开", "点击", "输入", "搜索", "返回", "发送", "查看")
            .count { g.contains(it) }
        return verbCount <= 1
    }
}
