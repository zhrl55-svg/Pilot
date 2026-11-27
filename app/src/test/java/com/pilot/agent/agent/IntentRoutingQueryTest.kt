package com.pilot.agent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「搜索新闻」类强查询必须走查询通道（news_search / web_search），不能被当成操作手机。
 * 只有指定了 App 的搜索（在淘宝搜/打开微博搜）或“打开给我看”才算执行。
 */
class IntentRoutingQueryTest {

    private val strongQueries = listOf(
        "搜索新闻",
        "搜一下今天的财经新闻",
        "AI最近有什么动态",
        "搜索英伟达最新消息",
        "网页搜索 Kotlin 协程",
    )

    private val executions = listOf(
        "在淘宝搜索耳机",
        "打开微博搜新闻",
    )

    @Test
    fun `strong queries classify as query and are strong`() {
        for (q in strongQueries) {
            assertEquals("应判为查询：$q", IntentGate.Kind.QUERY, IntentGate.classify(q))
            assertTrue("应是强查询：$q", IntentGate.isStrongQuery(q))
        }
    }

    @Test
    fun `app scoped search is execution and not strong query`() {
        for (q in executions) {
            assertEquals("应判为执行：$q", IntentGate.Kind.TASK, IntentGate.classify(q))
            assertFalse("不应是强查询：$q", IntentGate.isStrongQuery(q))
            assertTrue("应识别为指定 App 的搜索：$q", IntentGate.isAppScopedSearch(q))
        }
    }

    @Test
    fun `visible webpage request is execution not query`() {
        assertFalse(IntentGate.isStrongQuery("打开网页给我看"))
        assertEquals(IntentGate.Kind.TASK, IntentGate.classify("打开网页给我看"))
    }

    @Test
    fun `plain app open is unaffected`() {
        assertEquals(IntentGate.Kind.TASK, IntentGate.classify("打开设置"))
        assertFalse(IntentGate.isStrongQuery("打开设置"))
        assertFalse(IntentGate.isAppScopedSearch("打开设置"))
    }
}
