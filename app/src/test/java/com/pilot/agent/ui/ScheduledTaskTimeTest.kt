package com.pilot.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale

/**
 * 定时任务面板的时间解析契约。
 *
 * 面板用「MM-dd HH:mm」文本输入，解析必须稳定：
 * - 合法格式 → 落到今年/明年的正确时刻
 * - 非法格式 → null（UI 明确报错，不静默丢弃用户的输入）
 */
class ScheduledTaskTimeTest {

    @Test
    fun `parses a valid time into the current year`() {
        val at = parseTaskTime("11-24 08:00")!!
        val cal = Calendar.getInstance(Locale.CHINA).apply { timeInMillis = at }
        val now = Calendar.getInstance(Locale.CHINA)
        assertEquals(11, cal.get(Calendar.MONTH) + 1)
        assertEquals(24, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(8, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, cal.get(Calendar.MINUTE))
        // 不应是 1970 年（SimpleDateFormat 的默认年份陷阱）
        assertTrue(cal.get(Calendar.YEAR) >= now.get(Calendar.YEAR))
    }

    @Test
    fun `accepts fullwidth colon from Chinese IME`() {
        // 中文输入法常把 ":" 打成 "："，用户看着没错却提示格式错误
        val at = parseTaskTime("11-24 08：00")!!
        val cal = Calendar.getInstance(Locale.CHINA).apply { timeInMillis = at }
        assertEquals(8, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, cal.get(Calendar.MINUTE))
    }

    @Test
    fun `rejects malformed input`() {
        assertNull(parseTaskTime(""))
        assertNull(parseTaskTime("明天早上"))
        assertNull(parseTaskTime("13-45 99:99"))
    }

    @Test
    fun `round trips through the display format`() {
        val at = parseTaskTime("11-24 08:00")!!
        val text = java.text.SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
            .format(java.util.Date(at))
        assertEquals("11-24 08:00", text)
    }
}
