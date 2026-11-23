package com.pilot.agent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 规划器链顺序契约。
 *
 * 顺序是性能与正确性的折中，改动很容易把「一句音量调节」重新推给云端模型：
 * - 规则层永远先试（成本 0）；
 * - 助理模式：短句先给端侧小模型，长句直接上云；
 * - Agent 模式：云端主导，但首步若是确定性系统直调，规则层优先；
 * - 云端未配置时，端侧/规则兜底，保证不是哑巴。
 */
class PlannerRouterTest {

    private val settings = AgentSettings()

    private val cloud = CloudPlanner { settings }

    private val onDevice = HeuristicPlanner()

    // 链顺序测试不执行规划器；这里只要一个可判别身份的真实实例。
    private val local = LocalPlanner {
        throw IllegalStateException("chain 顺序测试不应调用端侧规划器")
    }

    private fun router(settings: AgentSettings): PlannerRouter =
        PlannerRouter({ settings }, cloud, onDevice, local)

    private fun chain(
        goal: String,
        mode: AssistantMode = AssistantMode.ASSISTANT,
        cloudReady: Boolean = true,
        localModel: Boolean = true,
        stepIndex: Int = 0,
    ): List<Planner> = router(
        AgentSettings(
            mode = mode,
            apiKey = if (cloudReady) "test-key" else "",
            baseUrl = "https://example.invalid/v1",
            enableLocalModel = localModel,
        ),
    ).chain(goal, stepIndex)

    private fun assertOrder(expected: List<Planner>, actual: List<Planner>) {
        assertEquals("规划器数量", expected.size, actual.size)
        expected.forEachIndexed { index, planner ->
            assertSame("第 ${index + 1} 个规划器", planner, actual[index])
        }
    }

    // Test scenario: 助理模式下短单步请求先给端侧小模型，云端作为后备。
    @Test
    fun `assistant mode puts local model before cloud for short goals`() {
        assertOrder(
            listOf(onDevice, local, cloud),
            chain("打开微信"),
        )
    }

    // Test scenario: 长句（>14 字）不交给 0.6B，先上云，端侧只在云端之后兜底。
    @Test
    fun `assistant mode defers local model behind cloud for long goals`() {
        assertOrder(
            listOf(onDevice, cloud, local),
            chain("帮我整理一下今天的会议纪要并发给同事"),
        )
    }

    // Test scenario: 多步连接词出现时同样先上云。
    @Test
    fun `assistant mode treats connector phrases as multi step`() {
        assertOrder(
            listOf(onDevice, cloud, local),
            chain("打开设置然后帮我点电池"),
        )
    }

    // Test scenario: 关掉端侧模型后端侧不再出现在链里。
    @Test
    fun `assistant mode skips local model when disabled`() {
        assertOrder(
            listOf(onDevice, cloud),
            chain("打开微信", localModel = false),
        )
    }

    // Test scenario: 云端未配置时仍保留端侧模型兜底。
    @Test
    fun `assistant mode keeps local fallback without cloud`() {
        assertOrder(
            listOf(onDevice, local),
            chain("打开微信", cloudReady = false),
        )
    }

    // Test scenario: Agent 模式首步命中确定性系统直调时，规则层排在云端前面。
    @Test
    fun `agent mode tries deterministic system op before cloud`() {
        assertOrder(
            listOf(onDevice, cloud, local),
            chain("把音量调到 50%", mode = AssistantMode.AGENT),
        )
    }

    // Test scenario: 同一句确定性指令在第二步之后不再抢跑，云端恢复优先。
    @Test
    fun `agent mode only prioritises deterministic op on the first step`() {
        assertOrder(
            listOf(cloud, onDevice, local),
            chain("把音量调到 50%", mode = AssistantMode.AGENT, stepIndex = 1),
        )
    }

    // Test scenario: 非确定性的复杂目标由云端主导，规则层只做后备。
    @Test
    fun `agent mode puts cloud first for non deterministic goals`() {
        assertOrder(
            listOf(cloud, onDevice, local),
            chain("帮我在设置里找一下电池优化并关掉", mode = AssistantMode.AGENT),
        )
    }

    // Test scenario: Agent 模式云端不可用时，规则层兜底且端侧模型继续可用。
    @Test
    fun `agent mode falls back to rule planner without cloud`() {
        assertOrder(
            listOf(onDevice, local),
            chain("帮我在设置里找一下电池优化", mode = AssistantMode.AGENT, cloudReady = false),
        )
    }

    // Test scenario: 三个访问器返回链里使用的同一批实例，避免出现影子规划器。
    @Test
    fun `accessors expose the same planner instances`() {
        val plannerRouter = router(settings)

        assertSame(cloud, plannerRouter.cloud())
        assertSame(onDevice, plannerRouter.onDevice())
        assertSame(local, plannerRouter.local())
    }
}