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
}
