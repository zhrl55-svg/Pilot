package com.pilot.agent.query

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 天气查询的路由契约：只接天气类问句，不抢网页搜索/闲聊。 */
class WeatherToolTest {

    private val tool = WeatherTool()

    @Test
    fun `matches weather questions`() {
        assertTrue(tool.matches("今天天气怎么样"))
        assertTrue(tool.matches("查一下张掖的天气"))
        assertTrue(tool.matches("明天会下雨吗"))
        assertTrue(tool.matches("现在气温多少"))
    }

    @Test
    fun `does not match unrelated questions`() {
        assertFalse(tool.matches("搜一下新闻"))
        assertFalse(tool.matches("播放一首歌"))
        assertFalse(tool.matches("附近的药店"))
        assertFalse(tool.matches("讲个故事"))
    }
}
