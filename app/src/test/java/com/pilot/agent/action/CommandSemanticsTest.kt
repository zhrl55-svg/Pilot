package com.pilot.agent.action

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 系统命令 A/B/C 语义与回执纪律（m-20261127-104）。
 *
 * A 导航：只到页面；B 结果：必须走状态写入；C 做不到：先说原因再问是否带路。
 * 这里是纯文本判定，不碰系统服务。
 */
class CommandSemanticsTest {

    private fun kind(goal: String): CommandKind? = CommandSemantics.classify(goal)?.kind

    // ── A/B 对照 ────────────────────────────────────────────────

    @Test
    fun `brightness absolute is a result command`() {
        val cmd = CommandSemantics.classify("亮度调到30%")
        assertNotNull(cmd)
        assertEquals(CommandKind.RESULT, cmd!!.kind)
        assertEquals(CommandTarget.BRIGHTNESS, cmd.target)
    }

    @Test
    fun `looking at brightness is navigation`() {
        assertEquals(CommandKind.NAVIGATE, kind("看看亮度"))
    }

    @Test
    fun `screen a bit brighter is a result command`() {
        assertEquals(CommandKind.RESULT, kind("屏幕亮一点"))
    }

    @Test
    fun `volume up is a result command`() {
        assertEquals(CommandKind.RESULT, kind("声音大一点"))
    }

    @Test
    fun `dnd off is a result command`() {
        val cmd = CommandSemantics.classify("关掉勿扰")
        assertEquals(CommandKind.RESULT, cmd!!.kind)
        assertEquals(CommandTarget.DND, cmd.target)
        assertEquals(false, cmd.on)
    }

    // ── C 类与导航的区分 ────────────────────────────────────────

    @Test
    fun `turning bluetooth off is impossible`() {
        val cmd = CommandSemantics.classify("把蓝牙关掉")
        assertEquals(CommandKind.IMPOSSIBLE, cmd!!.kind)
        assertEquals(CommandTarget.BLUETOOTH, cmd.target)
    }

    @Test
    fun `turning bluetooth on is impossible too`() {
        assertEquals(CommandKind.IMPOSSIBLE, kind("打开蓝牙"))
    }

    @Test
    fun `opening the bluetooth settings page is navigation`() {
        assertEquals(CommandKind.NAVIGATE, kind("打开蓝牙设置"))
    }

    @Test
    fun `wifi toggle is impossible`() {
        assertEquals(CommandKind.IMPOSSIBLE, kind("把wifi关掉"))
    }

    @Test
    fun `battery saver is impossible`() {
        assertEquals(CommandKind.IMPOSSIBLE, kind("开省电模式"))
    }

    @Test
    fun `question about bluetooth is not a command`() {
        assertNull(CommandSemantics.classify("蓝牙怎么打开"))
    }

    @Test
    fun `plain open settings is not classified here`() {
        assertNull(CommandSemantics.classify("打开设置"))
    }

    // ── 回执三态 ────────────────────────────────────────────────

    @Test
    fun `needs-user text states reason and offers a guide`() {
        val cmd = CommandSemantics.classify("把蓝牙关掉")!!
        val msg = CommandSemantics.needsUserText(cmd)
        assertTrue(msg.contains("没办法"))
        assertTrue(msg.contains("Bluetooth") || msg.contains("蓝牙"))
        assertTrue(msg.contains("带你"))
    }

    @Test
    fun `guide opened text is honest about blocked launch`() {
        val ok = CommandSemantics.guideOpenedText(CommandTarget.WIFI, LaunchOutcome.Ok)
        assertTrue(ok.contains("已打开"))
        val blocked = CommandSemantics.guideOpenedText(
            CommandTarget.WIFI,
            LaunchOutcome.Blocked("前台没有切换"),
        )
        assertFalse(blocked.contains("已打开"))
        assertTrue(blocked.contains("没能打开"))
    }

    @Test
    fun `affirmative replies are recognized but not normal commands`() {
        assertTrue(CommandSemantics.isAffirmative("好的"))
        assertTrue(CommandSemantics.isAffirmative("带我去"))
        assertFalse(CommandSemantics.isAffirmative("算了"))
        assertFalse(CommandSemantics.isAffirmative("帮我查一下明天的火车票"))
    }

    // ── 目录兜底：结果型措辞不得变成「打开设置页」 ───────────────

    @Test
    fun `catalog refuses result phrasing`() {
        assertNull(ActionCatalog.resolve("把蓝牙关掉"))
        assertNotNull(ActionCatalog.resolve("打开蓝牙设置"))
    }
}
