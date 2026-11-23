package com.pilot.agent.action

import android.content.Context
import com.pilot.agent.testutil.FakeContext
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 本地提醒的取消契约。
 *
 * 旧实现遇到空关键词会直接取消最早一条 —— 用户只说了「取消提醒」，
 * 结果一条不相干的提醒被删掉。现在的契约是：**不猜**，先把列表交给用户。
 * 这里锁定这条回归，同时覆盖时间校验与列表入口。
 */
class ReminderManagerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = FakeContext()
    }

    // Test scenario: 空字符串关键词不得删除任何提醒，必须先要求用户查看列表。
    @Test
    fun `empty keyword never cancels a reminder`() {
        val reply = ReminderManager.cancel(context, "")

        assertEquals("要取消哪条提醒？说“我的提醒”可以先看看列表。", reply)
        assertFalse(reply.contains("已取消"))
    }

    // Test scenario: 只有空白与标点的关键词同样视为空，不能当成标题去匹配。
    @Test
    fun `blank punctuation keyword is treated as empty`() {
        val reply = ReminderManager.cancel(context, "  ：，,。  ")

        assertTrue(reply.startsWith("要取消哪条提醒？"))
        assertFalse(reply.contains("已取消"))
    }

    // Test scenario: 「取消提醒」不带标题时，handle 抽出空关键词，同样不删数据。
    @Test
    fun `bare cancel phrase asks for the list instead of deleting`() {
        val reply = ReminderManager.handle(context, "取消提醒")

        assertEquals("要取消哪条提醒？说“我的提醒”可以先看看列表。", reply)
    }

    // Test scenario: 有关键词但列表为空时明确报未找到，而不是静默成功。
    @Test
    fun `unknown keyword reports not found`() {
        val reply = ReminderManager.handle(context, "取消提醒 开会")

        assertEquals("没有找到要取消的提醒。说“我的提醒”可以查看列表。", reply)
    }

    // Test scenario: 没有任何提醒时列表入口给出稳定的空列表文案。
    @Test
    fun `list text is empty when nothing is scheduled`() {
        assertEquals("现在没有待触发的提醒。", ReminderManager.listText(context))
    }

    // Test scenario: 结构化入口拒绝空白标题，避免建出无法识别的提醒。
    @Test
    fun `blank title is rejected by parsed entry point`() {
        val at = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(5)

        assertNull(ReminderManager.createFromParsed(context, "   ", at))
        // 拒绝之后仓库仍是空的 —— 不能出现「没返回结果但已经建了提醒」。
        assertEquals("现在没有待触发的提醒。", ReminderManager.listText(context))
    }

    // Test scenario: 过去的时间不是有效提醒时间。
    @Test
    fun `past time is rejected`() {
        val reply = ReminderManager.createFromParsed(
            context,
            "喝水",
            System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(1),
        )

        assertEquals("提醒时间需要在现在之后、未来 30 天以内。", reply)
    }

    // Test scenario: 超过 30 天的提醒不被接受（无界面场景无法可靠触发）。
    @Test
    fun `beyond thirty days is rejected`() {
        val reply = ReminderManager.createFromParsed(
            context,
            "喝水",
            System.currentTimeMillis() + TimeUnit.DAYS.toMillis(31),
        )

        assertEquals("提醒时间需要在现在之后、未来 30 天以内。", reply)
    }

    // Test scenario: 只有提醒类话术才会被本机接管，普通设备指令不进来。
    @Test
    fun `handles only reminder phrases`() {
        assertTrue(ReminderManager.handles("提醒我 30 分钟后喝水"))
        assertTrue(ReminderManager.handles("叫我起床"))
        assertTrue(ReminderManager.handles("我的提醒"))
        assertFalse(ReminderManager.handles("打开设置"))
        assertFalse(ReminderManager.handles("现在几点"))
    }

    // Test scenario: 重建闹钟时，空仓库不会安排任何提醒（旧提醒不会在启动时复活）。
    @Test
    fun `reschedule on empty store is a no-op`() {
        ReminderManager.rescheduleAll(context)

        assertEquals("现在没有待触发的提醒。", ReminderManager.listText(context))
    }

    // Test scenario: 通知回调针对未知 id 时直接返回，不抛异常打断广播。
    @Test
    fun `fire with unknown id returns quietly`() {
        ReminderManager.fire(context, "r-missing")

        assertEquals("现在没有待触发的提醒。", ReminderManager.listText(context))
    }

    // Test scenario: 提醒触发广播的 action 常量属于对外契约，改名会断开已排的闹钟。
    @Test
    fun `broadcast action constants stay stable`() {
        assertEquals("com.pilot.agent.REMINDER_FIRE", ReminderManager.ACTION_FIRE)
        assertEquals("com.pilot.agent.REMINDER_BOOT", ReminderManager.ACTION_BOOT)
        assertEquals("reminder_id", ReminderManager.EXTRA_ID)
    }

    // Test scenario: 列表入口经 handle 分发（「我的提醒」），调用方不必自己判断。
    @Test
    fun `handle routes the list phrase to listText`() {
        assertEquals("现在没有待触发的提醒。", ReminderManager.handle(context, "我的提醒"))
    }

    // Test scenario: 「删除提醒」与「取消提醒」共用同一条取消路径，空标题同样不删数据。
    @Test
    fun `delete phrase without a title asks for the list`() {
        assertEquals(
            "要取消哪条提醒？说“我的提醒”可以先看看列表。",
            ReminderManager.handle(context, "删除提醒"),
        )
    }

    // Test scenario: 解析不了时间时必须明说不确定，不能假装已经设好提醒。
    @Test
    fun `unparsable time is reported as unknown`() {
        assertEquals(
            "我还不能确定提醒时间。请说“30 分钟后提醒我喝水”或“2 小时后提醒我开会”。",
            ReminderManager.handle(context, "提醒我 喝水"),
        )
    }

    // Test scenario: 超出支持范围的相对时间（>720 分钟）不被接受，避免建出永不触发的提醒。
    @Test
    fun `relative time beyond the supported range is rejected`() {
        assertEquals(
            "我还不能确定提醒时间。请说“30 分钟后提醒我喝水”或“2 小时后提醒我开会”。",
            ReminderManager.handle(context, "九百分钟后提醒我喝水"),
        )
    }

    // Test scenario: 非法钟点（25 点）不能被悄悄取整成别的时间。
    @Test
    fun `invalid clock hour is rejected`() {
        assertEquals(
            "我还不能确定提醒时间。请说“30 分钟后提醒我喝水”或“2 小时后提醒我开会”。",
            ReminderManager.handle(context, "明天25点提醒我开会"),
        )
    }

    // Test scenario: 起床类口吻与「记得提醒」同样归本地提醒，别落到模型去点屏幕。
    @Test
    fun `wake up and remember phrases are reminder phrases`() {
        assertTrue(ReminderManager.handles("叫醒我"))
        assertTrue(ReminderManager.handles("喊我起床"))
        assertTrue(ReminderManager.handles("记得提醒我交水费"))
        assertTrue(ReminderManager.handles("删除提醒"))
        assertFalse(ReminderManager.handles("帮我写一篇总结"))
    }
}