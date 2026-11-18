package com.pilot.agent.assistant.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 路由匹配器的行为契约（移植自 Minis 的 `AssistantRouteMatcherTest`）。
 */
class AssistantRouteMatcherTest {

    private fun route(
        id: String,
        keywords: List<String>,
        priority: Int = 100,
        allKeywords: List<String> = emptyList(),
        exclude: List<String> = emptyList(),
        pattern: List<String> = emptyList(),
        capability: String = "device",
        action: String = "battery",
    ) = AssistantRoute(
        id = id,
        label = id,
        priority = priority,
        capability = capability,
        action = action,
        keywords = keywords,
        allKeywords = allKeywords,
        exclude = exclude,
        patterns = pattern,
    )

    @Test
    fun `matching normalizes width punctuation whitespace and case`() {
        val routes = listOf(route("battery", listOf("电量")))
        val text = "　电量　， 还剩多少？"
        assertEquals(1, AssistantRouteMatcher.rank(text, routes).size)
    }

    @Test
    fun `ascii keywords do not match inside identifiers`() {
        val routes = listOf(route("call", listOf("call"), capability = "device", action = "info"))
        assertTrue(AssistantRouteMatcher.rank("hub_call", routes).isEmpty())
        assertEquals(1, AssistantRouteMatcher.rank("call me", routes).size)
    }

    @Test
    fun `exclude terms drop the route`() {
        val routes = listOf(route("battery", listOf("电量"), exclude = listOf("加个")))
        assertTrue(AssistantRouteMatcher.rank("我们可以加个电量显示功能", routes).isEmpty())
    }

    @Test
    fun `all keywords must all be present`() {
        val routes = listOf(route("both", listOf("音量"), allKeywords = listOf("音乐")))
        assertTrue(AssistantRouteMatcher.rank("把音量调大", routes).isEmpty())
        assertEquals(1, AssistantRouteMatcher.rank("把音乐音量调大", routes).size)
    }

    @Test
    fun `disabled and unknown routes are skipped`() {
        val disabled = route("battery", listOf("电量")).copy(enabled = false)
        val unknown = route("ghost", listOf("电量"), capability = "not_a_capability", action = "nope")
        assertTrue(AssistantRouteMatcher.rank("电量还剩多少", listOf(disabled, unknown)).isEmpty())
    }

    @Test
    fun `ranking is by score not declaration order`() {
        val weak = route("weak", listOf("电量"))
        val strong = route("strong", listOf("剩余电量"))
        val ranked = AssistantRouteMatcher.rank("剩余电量还剩多少", listOf(weak, strong))
        assertEquals("strong", ranked.first().route.id)
    }

    @Test
    fun `patterns are matched case insensitively`() {
        val routes = listOf(
            route("write", listOf(), pattern = listOf("""^把\s+(.+?)\s+写入\s+/(.+)$""")),
        )
        val ranked = AssistantRouteMatcher.rank("把 hello 写入 /tmp/a.txt", routes)
        assertEquals(1, ranked.size)
        assertTrue(ranked.first().isStrong)
    }
}
