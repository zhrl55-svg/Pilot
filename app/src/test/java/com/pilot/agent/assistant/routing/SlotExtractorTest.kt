package com.pilot.agent.assistant.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 槽位抽取的行为契约（移植自 Minis 的 `SlotExtractorTest`，按 Pilot 的 v1 能力裁剪）。
 */
class SlotExtractorTest {

    private val extractor = SlotExtractor()

    private fun route(capability: String, action: String) =
        AssistantRoute(id = "test", label = "test", capability = capability, action = action)

    @Test
    fun `volume set reads an absolute percentage`() {
        val args = extract("音量调到 60%", "volume", "set")
        assertEquals(60, args.optInt("percent"))
    }

    @Test
    fun `volume set ignores a number that is not a percentage`() {
        val args = extract("音量调到 60 分", "volume", "set")
        assertTrue(args.optString("percent").isBlank())
    }

    @Test
    fun `volume adjust maps direction words to a step`() {
        assertEquals(10, extract("声音大一点", "volume", "adjust").optInt("percent"))
        assertEquals(-20, extract("调小音量", "volume", "adjust").optInt("percent"))
        assertEquals(40, extract("声音大很多", "volume", "adjust").optInt("percent"))
    }

    @Test
    fun `volume status never carries a percent`() {
        val args = extract("现在音量多少", "volume", "status")
        assertTrue(args.optString("percent").isBlank())
    }

    @Test
    fun `file read extracts the path verbatim`() {
        val args = extract("读一下 /sdcard/Download/a.txt", "file_read", "read")
        assertEquals("/sdcard/Download/a.txt", args.optString("path"))
    }

    @Test
    fun `file write extracts path and content from strict templates`() {
        val args = extract("把 hello 写入 /sdcard/Download/a.txt", "file_write", "write")
        assertEquals("/sdcard/Download/a.txt", args.optString("path"))
        assertEquals("hello", args.optString("content"))
    }

    @Test
    fun `memory write takes the text after the trigger`() {
        val args = extract("记住我喜欢喝咖啡", "memory_write", "write")
        assertEquals("我喜欢喝咖啡", args.optString("content"))
    }

    @Test
    fun `app launch keeps the app name`() {
        val args = extract("打开设置", "app_launch", "open")
        assertEquals("设置", args.optString("app"))
    }

    @Test
    fun `media play resolves a song request`() {
        val args = extract("播放晴天", "media", "play")
        assertEquals("song", args.optString("kind"))
        assertEquals("晴天", args.optString("query"))
    }

    @Test
    fun `media play without a target falls back to the chart`() {
        val args = extract("播放音乐", "media", "play")
        assertEquals("none", args.optString("kind"))
    }

    @Test
    fun `reminder title drops the time phrase`() {
        val args = extract("半小时后提醒我喝水", "reminder", "schedule")
        assertEquals("喝水", args.optString("title"))
        assertTrue(args.optLong("after") > 0L)
    }

    private fun extract(text: String, capability: String, action: String) =
        extractor.extract(text, route(capability, action)).args
}
