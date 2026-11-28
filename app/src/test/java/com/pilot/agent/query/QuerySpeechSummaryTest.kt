package com.pilot.agent.query

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播报摘要契约（m-20261128）：
 * - 新闻只念条目 + 文章/正文摘要，来源不播报；
 * - 网页搜索仍只念标题；
 * - 其他回答返回 null（完整播报）。
 */
class QuerySpeechSummaryTest {

    private val newsAnswer = """
        “特朗普”的新闻，范围：最近24小时（共 3 条）

        1. 特朗普宣布新关税政策
           来源：路透社 · 2 小时前（11-28 08:00）
           文章摘要：白宫表示将对进口商品加征关税，涉及多个行业。

        2. 特朗普会晤外国领导人
           来源：新华社 · 5 小时前（11-28 05:00）
           正文摘要：双方就贸易与安全议题交换意见，会后发表联合声明。

        3. 特朗普回应媒体提问
           来源：某报 · 1 天前（11-27 10:00）
           文章摘要：他就经济政策回答了记者提问。（原文暂时无法读取）

        来源：Google News RSS；已打开全部结果原文并提取正文摘要。
    """.trimIndent()

    @Test
    fun `news readout includes titles and summaries but not sources`() {
        val spoken = QueryEngine.speechSummary(newsAnswer)!!
        assertTrue(spoken.contains("找到 3 条新闻"))
        assertTrue(spoken.contains("第 1 条：特朗普宣布新关税政策"))
        assertTrue(spoken.contains("白宫表示将对进口商品加征关税"))
        assertTrue(spoken.contains("第 2 条：特朗普会晤外国领导人"))
        assertTrue(spoken.contains("双方就贸易与安全议题交换意见"))
        // 来源与抓取说明不进播报
        assertFalse(spoken.contains("来源"))
        assertFalse(spoken.contains("路透社"))
        assertFalse(spoken.contains("新华社"))
        assertFalse(spoken.contains("Google News"))
        assertFalse(spoken.contains("原文暂时无法读取"))
        assertFalse(spoken.contains("小时前"))
    }

    @Test
    fun `news without summary still reads titles only`() {
        val answer = """
            “AI”的新闻，范围：最近7天（共 2 条）

            1. 大模型发布新版本
               来源：某科技媒体 · 3 小时前（11-28 07:00）

            2. AI 芯片需求上涨
               来源：财经网 · 6 小时前（11-28 04:00）
        """.trimIndent()
        val spoken = QueryEngine.speechSummary(answer)!!
        assertTrue(spoken.contains("找到 2 条新闻"))
        assertTrue(spoken.contains("第 1 条：大模型发布新版本"))
        assertTrue(spoken.contains("第 2 条：AI 芯片需求上涨"))
        assertFalse(spoken.contains("来源"))
    }

    @Test
    fun `garbage html fragment summary is not spoken`() {
        val answer = """
            “科技”的新闻，范围：最近7天（共 1 条）

            1. 瑞松科技盘中涨停
               来源：证券之星 · 4 天前（11-23 14:14）
               文章摘要：/font（原文暂时无法读取）
        """.trimIndent()
        val spoken = QueryEngine.speechSummary(answer)!!
        assertTrue(spoken.contains("第 1 条：瑞松科技盘中涨停"))
        assertFalse(spoken.contains("/font"))
        assertFalse(spoken.contains("font"))
        assertFalse(spoken.contains("原文暂时无法读取"))
    }

    @Test
    fun `news header without redundant suffix still recognized`() {
        // 主题已含「新闻」时结果头不再追加“的新闻”（“国际 新闻”，范围：…）。
        val answer = """
            “国际 新闻”，范围：最近24小时（共 2 条）

            1. 西班牙央行前行长将出任欧洲央行行长
               来源：红网 · 34 分钟前（11-28 10:54）
               文章摘要：欧洲央行宣布新任行长人选，将于明年正式履新。

            2. 国际金价大幅上涨重返3650美元
               来源：正观新闻 · 1 小时前（11-28 10:13）
        """.trimIndent()
        val spoken = QueryEngine.speechSummary(answer)!!
        assertTrue(spoken.contains("找到 2 条新闻"))
        assertTrue(spoken.contains("第 1 条：西班牙央行前行长将出任欧洲央行行长"))
        assertTrue(spoken.contains("欧洲央行宣布新任行长人选"))
        assertFalse(spoken.contains("来源"))
        assertFalse(spoken.contains("红网"))
    }

    @Test
    fun `web search readout keeps titles only`() {
        val answer = """
            网页搜索：特朗普 行程（2 条）

            1. 特朗普近期行程安排一览 · 2 days ago
               这是网页描述，不应被播报。
               正文摘要：正文内容也不应被播报。

            2. 白宫公布总统日程
               另一段描述。
        """.trimIndent()
        val spoken = QueryEngine.speechSummary(answer)!!
        assertTrue(spoken.contains("找到 2 条结果"))
        assertTrue(spoken.contains("第 1 条：特朗普近期行程安排一览"))
        assertFalse(spoken.contains("这是网页描述"))
        assertFalse(spoken.contains("正文内容"))
    }

    @Test
    fun `non search answers return null for full readout`() {
        assertNull(QueryEngine.speechSummary("今天天气晴，25 度。"))
        assertNull(QueryEngine.speechSummary("已完成：音量调到 50%。"))
    }

    @Test
    fun `train readout unchanged`() {
        val answer = """
            12306 余票：北京 → 上海（2026-11-29）

            1. G1 07:00→11:29 二等座 有票
            2. G3 08:00→12:31 二等座 候补
        """.trimIndent()
        val spoken = QueryEngine.speechSummary(answer)!!
        assertTrue(spoken.startsWith("查到 2 趟"))
        assertEquals(true, spoken.contains("G1"))
    }
}