package com.pilot.agent.action

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日程启动回执（审核 P0-5 / D-01）。
 *
 * HarmonyOS 后台启动拦截是静默的：startActivity 不抛异常、前台也不动。
 * 回执只有拿到 [LaunchOutcome.Ok] 才能说「已打开」，其余结局必须如实说
 * 「没创建」或「没确认」，不能再谎报成功。
 */
class CalendarToolTest {

    @Test
    fun `ok outcome reports opened`() {
        val msg = CalendarTool.messageFor(LaunchOutcome.Ok)
        assertTrue(msg.contains("已打开日历新建页面"))
    }

    @Test
    fun `blocked outcome must not claim success`() {
        val msg = CalendarTool.messageFor(
            LaunchOutcome.Blocked("前台没有切换，可能被系统后台启动限制拦截"),
        )
        assertFalse(msg.contains("已打开"))
        assertTrue(msg.contains("日程没有创建"))
    }

    @Test
    fun `unknown outcome must say not confirmed`() {
        val msg = CalendarTool.messageFor(LaunchOutcome.Unknown)
        assertFalse(msg.contains("已打开"))
        assertTrue(msg.contains("结果未知"))
    }

    @Test
    fun `failed outcome points at missing calendar app`() {
        val msg = CalendarTool.messageFor(LaunchOutcome.Failed(RuntimeException("no activity")))
        assertFalse(msg.contains("已打开"))
        assertTrue(msg.contains("没有找到可用的日历应用"))
    }
}
