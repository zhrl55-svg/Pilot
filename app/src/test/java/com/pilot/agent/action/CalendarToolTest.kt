package com.pilot.agent.action

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日程直写系统日历（m-20261127-103）。
 *
 * 覆盖验收要求里的四条回执文案（成功 / 重复 / 无权限 / 无可写日历），
 * 以及时间解析复用 [com.pilot.agent.assistant.routing.ChineseTimeParser] 的三种输入形态。
 * 全部是纯 JVM 断言：不碰 ContentResolver，也不碰日历 App。
 */
class CalendarToolTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val now: LocalDateTime = LocalDateTime.of(2026, 11, 27, 21, 0)

    // ── 时间解析 ────────────────────────────────────────────────

    @Test
    fun `parses tomorrow afternoon with explicit hour`() {
        val goal = CalendarTool.parseGoal("添加日程 明天下午3点项目讨论", now, zone)
        assertNotNull(goal)
        val start = goal!!.startMs
        val startLocal = java.time.Instant.ofEpochMilli(start).atZone(zone).toLocalDateTime()
        assertEquals(28, startLocal.dayOfMonth)
        assertEquals(15, startLocal.hour)
        assertEquals(0, startLocal.minute)
        assertEquals("项目讨论", goal.title)
        assertEquals(60L * 60L * 1000L, goal.endMs - goal.startMs)
    }

    @Test
    fun `parses day after tomorrow with colon time`() {
        val goal = CalendarTool.parseGoal("安排会议 后天 9:30 周会", now, zone)
        assertNotNull(goal)
        val startLocal = java.time.Instant.ofEpochMilli(goal!!.startMs).atZone(zone).toLocalDateTime()
        assertEquals(29, startLocal.dayOfMonth)
        assertEquals(9, startLocal.hour)
        assertEquals(30, startLocal.minute)
        assertEquals("周会", goal.title)
    }

    @Test
    fun `missing time is rejected so the caller asks for it`() {
        assertNull(CalendarTool.parseGoal("添加日程 项目讨论", now, zone))
    }

    @Test
    fun `missing title is rejected`() {
        assertNull(CalendarTool.parseGoal("添加日程 明天下午3点", now, zone))
    }

    // ── 回执文案（验收 #6 四条路径）────────────────────────────

    @Test
    fun `created message says added to calendar`() {
        val msg = CalendarTool.messageFor(CalendarTool.CalendarResult.Created("明天 15:00", "项目讨论"))
        assertEquals("已加到日历：明天 15:00 项目讨论", msg)
    }

    @Test
    fun `duplicate message says not created again`() {
        val msg = CalendarTool.messageFor(CalendarTool.CalendarResult.Duplicate("明天 15:00", "项目讨论"))
        assertTrue(msg.contains("没有重复创建"))
        assertFalse(msg.contains("已加到日历"))
    }

    @Test
    fun `no permission falls back to the editor honestly`() {
        val msg = CalendarTool.messageFor(
            CalendarTool.CalendarResult.Failed("没有日历读写权限", "明天 15:00", "项目讨论", LaunchOutcome.Ok),
        )
        assertFalse(msg.contains("已加到日历"))
        assertTrue(msg.contains("没有日历读写权限"))
        assertTrue(msg.contains("已打开日历新建页"))
    }

    @Test
    fun `no writable calendar and blocked fallback never claims success`() {
        val msg = CalendarTool.messageFor(
            CalendarTool.CalendarResult.Failed(
                "没有可写入的日历", "明天 15:00", "项目讨论",
                LaunchOutcome.Blocked("前台没有切换"),
            ),
        )
        assertFalse(msg.contains("已加到日历"))
        assertFalse(msg.contains("已打开日历新建页"))
        assertTrue(msg.contains("没有可写入的日历"))
        assertTrue(msg.contains("日程没有创建"))
    }

    @Test
    fun `insert failure with unknown launch result stays honest`() {
        val msg = CalendarTool.messageFor(
            CalendarTool.CalendarResult.Failed("写入日历失败", "明天 15:00", "项目讨论", LaunchOutcome.Unknown),
        )
        assertFalse(msg.contains("已加到日历"))
        assertTrue(msg.contains("写入日历失败"))
        assertTrue(msg.contains("结果未知"))
    }

    // ── 可写日历选择规则 ──────────────────────────────────────

    @Test
    fun `writable calendar prefers primary then lowest id`() {
        val picked = CalendarStore.pick(
            listOf(
                CalendarStore.WritableCalendar(2L, "Birthday", 200, primary = false), // 只读，忽略
                CalendarStore.WritableCalendar(7L, "Work", 500, primary = false),
                CalendarStore.WritableCalendar(3L, "Phone", 700, primary = true),
            ),
        )
        assertEquals(3L, picked?.id)
    }

    @Test
    fun `no writable calendar yields null`() {
        assertNull(
            CalendarStore.pick(listOf(CalendarStore.WritableCalendar(2L, "Birthday", 200, false))),
        )
    }
}
