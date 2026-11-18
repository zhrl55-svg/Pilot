package com.pilot.agent.assistant.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 路由器在「含糊时间」与「端侧补值」叠加时的契约。
 *
 * 回归背景（P5 真机验收发现）：「保存到 /x.txt 内容 明天开会」里的「明天」
 * 会被时间解析器当成时间，`ambiguous=true` 走进含糊时间分支；旧实现该分支
 * 固定 `needsModelExtraction=false`，于是 content 永远没人补，plan 以
 * no-executor 回落 —— 确认框也就永远不出现。
 */
class AssistantRouterTest {

    private val router = AssistantRouter()

    @Test
    fun `ambiguous time inside file content still allows model extraction`() {
        val decision = router.route(
            "保存到 /sdcard/Download/p5_matrix.txt 内容 明天开会",
            DefaultRoutes.all,
        )
        assertTrue("应当本地接管", decision is RouteDecision.Local)
        decision as RouteDecision.Local
        assertEquals("file_write", decision.capability)
        assertEquals("write", decision.action)
        assertTrue("「明天」在正文里，应标记含糊时间", decision.ambiguousTime)
        assertTrue("content 应交给端侧模型补", decision.needsModelExtraction)
        assertEquals(setOf("content"), decision.missingSlots)
    }

    @Test
    fun `a fully extracted write does not ask for model extraction`() {
        val decision = router.route("把 hello 写入 /sdcard/Download/a.txt", DefaultRoutes.all)
        assertTrue(decision is RouteDecision.Local)
        decision as RouteDecision.Local
        assertTrue(!decision.needsModelExtraction)
        assertTrue(decision.missingSlots.isEmpty())
    }

    @Test
    fun `asking what you remember means listing all of it`() {
        // 遗留链路这里走 memory_list（列全部）。快路径之前一律当搜索
        // （keywords="什么"），结果和遗留不一致 —— memory_get 当时已是 ROUTE。
        listOf("你记得什么", "列出你记住", "查看长期记忆").forEach { text ->
            val decision = router.route(text, DefaultRoutes.all)
            assertTrue("「$text」应当本地接管", decision is RouteDecision.Local)
            decision as RouteDecision.Local
            assertEquals("memory_get", decision.capability)
            assertEquals("「$text」应当列全部而不是搜索", "list", decision.action)
        }
    }

    @Test
    fun `asking about something specific still searches`() {
        val decision = router.route("你还记得我喜欢喝什么吗", DefaultRoutes.all)
        assertTrue(decision is RouteDecision.Local)
        decision as RouteDecision.Local
        assertEquals("memory_get", decision.capability)
        assertEquals("search", decision.action)
    }
}
