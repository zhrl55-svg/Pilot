package com.pilot.agent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.pilot.agent.query.WebSearchTool

/**
 * 强查询边界回归（m-20261128）：浏览器操作任务不能被「网页」等词误判成 web_search。
 */
class StrongQueryBrowserTaskTest {

    @Test
    fun `browser open with url is not strong query`() {
        assertFalse(IntentGate.isStrongQuery("打开 https://example.com 然后截取网页画面"))
        assertFalse(IntentGate.isStrongQuery("用浏览器打开 example.com，把网页画面保存成图片"))
        assertFalse(IntentGate.isStrongQuery("访问 https://www.bing.com 并点击登录"))
    }

    @Test
    fun `browser operation intent is not strong query`() {
        assertFalse(IntentGate.isStrongQuery("用浏览器截屏当前页面"))
        assertFalse(IntentGate.isStrongQuery("浏览器里填写表单并提交"))
    }

    @Test
    fun `genuine info queries remain strong query`() {
        assertTrue(IntentGate.isStrongQuery("今天有什么国际新闻"))
        assertTrue(IntentGate.isStrongQuery("帮我搜一下人工智能最新进展"))
        assertTrue(IntentGate.isStrongQuery("搜一下最近的财经资讯"))
    }

    @Test
    fun `plain web search without browser task stays strong query`() {
        assertTrue(IntentGate.isStrongQuery("网页搜索 量子计算 原理"))
        assertTrue(IntentGate.isStrongQuery("搜一下特朗普近期行程"))
    }

    @Test
    fun `bare word webpage alone is not strong query`() {
        // 「网页」不再单独触发 web_search：浏览器操作/普通描述里出现「网页」不该走搜索。
        assertFalse(IntentGate.isStrongQuery("打开这个网页"))
        assertFalse(IntentGate.isStrongQuery("把网页画面保存成图片"))
        assertFalse(IntentGate.isStrongQuery("这个网页写得不错"))
    }

    @Test
    fun `web search tool does not match bare webpage word`() {
        assertFalse(WebSearchTool().matches("打开这个网页"))
        assertFalse(WebSearchTool().matches("把网页画面保存成图片"))
        assertTrue(WebSearchTool().matches("帮我搜一下量子计算"))
        assertTrue(WebSearchTool().matches("网页搜索 最新手机"))
    }

    @Test
    fun `browser task classifies as task not query`() {
        assertEquals(IntentGate.Kind.TASK, IntentGate.classify("用浏览器打开 example.com，把网页画面保存成图片"))
        assertEquals(IntentGate.Kind.TASK, IntentGate.classify("打开 https://example.com 然后截取网页画面"))
        assertEquals(IntentGate.Kind.TASK, IntentGate.classify("用浏览器截屏当前页面"))
    }

    @Test
    fun `isBrowserTask detects url and browser operations`() {
        assertTrue(IntentGate.isBrowserTask("用浏览器打开 https://www.qq.com 并截图"))
        assertTrue(IntentGate.isBrowserTask("打开 https://example.com 然后截取网页画面"))
        assertTrue(IntentGate.isBrowserTask("用浏览器截屏当前页面"))
        assertFalse(IntentGate.isBrowserTask("今天有什么国际新闻"))
        assertFalse(IntentGate.isBrowserTask("打开设置"))
        assertFalse(IntentGate.isBrowserTask("搜一下特朗普近期行程"))
    }
}