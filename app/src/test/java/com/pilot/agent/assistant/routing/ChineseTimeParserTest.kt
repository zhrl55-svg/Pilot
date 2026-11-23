package com.pilot.agent.assistant.routing

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 中文时间解析的行为契约（移植自 Minis 的 `ChineseTimeParserTest`）。
 */
class ChineseTimeParserTest {

    private val now: LocalDateTime = LocalDateTime.of(2026, 11, 18, 10, 0, 0)

    @Test
    fun `relative durations are computed from now`() {
        val parsed = ChineseTimeParser.parse("半小时后", now)!!
        assertTrue(parsed.relative)
        assertEquals(now.plusSeconds(1800L), parsed.dateTime)
    }

    @Test
    fun `tomorrow afternoon keeps the day and the period`() {
        val parsed = ChineseTimeParser.parse("明天下午 3 点", now)!!
        assertEquals(now.toLocalDate().plusDays(1), parsed.dateTime.toLocalDate())
        assertEquals(15, parsed.dateTime.hour)
    }

    @Test
    fun `daily cadence is detected`() {
        val parsed = ChineseTimeParser.parse("每天 8 点", now)!!
        assertEquals(ChineseTimeParser.RepeatMode.DAILY, parsed.repeat)
        assertEquals(8, parsed.dateTime.hour)
    }

    @Test
    fun `an early-morning clock time is ambiguous`() {
        val parsed = ChineseTimeParser.parse("3 点", now)!!
        assertTrue(parsed.ambiguous)
    }

    @Test
    fun `tomorrow evening 8pm uses evening hour`() {
        // P1 回归：原来"明晚8点"被解析成明早 8 点且 isConfident=true。
        val parsed = ChineseTimeParser.parse("明晚8点", now)!!
        assertEquals(now.toLocalDate().plusDays(1), parsed.dateTime.toLocalDate())
        assertEquals(20, parsed.dateTime.hour)
    }

    @Test
    fun `noon 10am stays in the morning`() {
        // P1 回归：原来"中午10点"被解析成 22:00。
        val parsed = ChineseTimeParser.parse("中午10点", now)!!
        assertEquals(10, parsed.dateTime.hour)
    }

    @Test
    fun `no time expression yields nothing`() {
        assertNull(ChineseTimeParser.parse("提醒我喝水", now))
    }

    @Test
    fun `durations sum their parts`() {
        val duration = ChineseTimeParser.parseDuration("1小时30分钟")!!
        assertEquals(5400L, duration.seconds)
    }

    @Test
    fun `time expressions are stripped for titles`() {
        val stripped = ChineseTimeParser.stripTimeExpressions("半小时后提醒我喝水")
        assertEquals("提醒我喝水", stripped)
    }
}
