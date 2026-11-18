package com.pilot.agent.assistant.llm

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 端侧补值的契约（移植自 Minis 的 `LocalIntentPlanner` 抽参规则）。
 *
 * 核心断言：模型补出来的值必须能追溯到用户原话；补不出来就返回 null，
 * 让调用方回落原链路 —— 绝不拿编造的值去执行。
 */
class SlotFillerTest {

    @Test
    fun `a value from the user text is merged`() {
        val filler = SlotFiller(
            available = { true },
            generate = { """{"content":"hello"}""" },
        )
        val merged = filler.fill(
            userText = "把 hello 写入 /tmp/a.txt",
            capability = "file_write",
            args = JSONObject().put("path", "/tmp/a.txt"),
            missing = setOf("content"),
        )
        assertEquals("hello", merged?.optString("content"))
        assertEquals("/tmp/a.txt", merged?.optString("path"))
    }

    @Test
    fun `an invented value is rejected`() {
        val filler = SlotFiller(
            available = { true },
            generate = { """{"content":"一段用户没说过的话"}""" },
        )
        val merged = filler.fill(
            userText = "把 hello 写入 /tmp/a.txt",
            capability = "file_write",
            args = JSONObject().put("path", "/tmp/a.txt"),
            missing = setOf("content"),
        )
        assertNull(merged)
    }

    @Test
    fun `a path outside the user text is rejected`() {
        val filler = SlotFiller(
            available = { true },
            generate = { """{"path":"/etc/hosts"}""" },
        )
        val parsed = filler.parse("""{"path":"/etc/hosts"}""", listOf("path"), "读一下 /tmp/a.txt")
        assertNull(parsed)
    }

    @Test
    fun `a substring of the user text is accepted for free text slots`() {
        val filler = SlotFiller({ true }, { null })
        val parsed = filler.parse(
            """{"content":"我喜欢喝咖啡"}""",
            listOf("content"),
            "记住我喜欢喝咖啡",
        )
        assertEquals("我喜欢喝咖啡", parsed?.optString("content"))
    }

    @Test
    fun `unknown keys and blank values are dropped`() {
        val filler = SlotFiller({ true }, { null })
        val parsed = filler.parse(
            """{"content":"","extra":"hello"}""",
            listOf("content"),
            "hello",
        )
        assertNull(parsed)
    }

    @Test
    fun `broken json is retried once`() {
        var calls = 0
        val filler = SlotFiller(
            available = { true },
            generate = {
                calls++
                if (calls == 1) """{"content": "hello""" else """{"content":"hello"}"""
            },
        )
        val merged = filler.fill(
            userText = "把 hello 写入 /tmp/a.txt",
            capability = "file_write",
            args = JSONObject().put("path", "/tmp/a.txt"),
            missing = setOf("content"),
        )
        assertEquals(2, calls)
        assertEquals("hello", merged?.optString("content"))
    }

    @Test
    fun `an unavailable model declines without calling generate`() {
        var calls = 0
        val filler = SlotFiller(
            available = { false },
            generate = { calls++; """{"content":"hello"}""" },
        )
        val merged = filler.fill("hello", "file_write", JSONObject(), setOf("content"))
        assertNull(merged)
        assertEquals(0, calls)
    }

    @Test
    fun `prompt uses real demo values and stays short`() {
        val filler = SlotFiller({ true }, { null })
        val prompt = filler.buildPrompt("把 hello 写入 /tmp/a.txt", "file_write", listOf("content"))
        assertTrue(prompt.contains("""{"content":"hello"}"""))
        assertTrue(!prompt.contains("示例值"))
        assertTrue("prompt should stay compact", prompt.length < 400)
    }
}
