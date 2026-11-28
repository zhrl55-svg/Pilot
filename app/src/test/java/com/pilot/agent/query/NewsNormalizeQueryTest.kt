package com.pilot.agent.query

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 新闻查询词清洗契约（m-20261128）：口语问句框架要洗掉，只留主题词，
 * 不能把「今天有没重要的国际」整句残片回显成“……的新闻”。
 */
class NewsNormalizeQueryTest {

    private val tool = NewsSearchTool()

    @Test
    fun `colloquial question reduces to topic`() {
        assertEquals("国际 新闻", tool.normalizeQuery("今天有没重要的国际新闻"))
        assertEquals("国际 新闻", tool.normalizeQuery("今天有没有重要的国际新闻"))
        assertEquals("国际 新闻", tool.normalizeQuery("最近有什么重大国际新闻"))
    }

    @Test
    fun `topic keywords preserved`() {
        assertEquals("财经 新闻", tool.normalizeQuery("财经新闻"))
        assertEquals("娱乐 新闻", tool.normalizeQuery("最新的娱乐新闻"))
        assertEquals("AI 人工智能 最新动态", tool.normalizeQuery("AI动态"))
    }

    @Test
    fun `search prefixes stripped`() {
        assertEquals("科技 新闻", tool.normalizeQuery("帮我搜一下科技新闻"))
        assertEquals("特朗普行程", tool.normalizeQuery("查一下特朗普行程"))
    }

    @Test
    fun `no question fragment leaks into query`() {
        val q = tool.normalizeQuery("今天有没重要的国际新闻")
        assertFalse(q.contains("今天"))
        assertFalse(q.contains("有没"))
        assertFalse(q.contains("重要"))
    }
}