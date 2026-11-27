package com.pilot.agent.tool

import android.content.Context
import com.pilot.agent.query.SecretVault
import com.pilot.agent.testutil.FakeContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * 定时任务的取消/新建契约。
 *
 * 和提醒一样，旧实现遇到空关键词会取消最早一条任务 —— 一次「取消定时任务」
 * 就可能让用户每天的电量播报消失。这里锁定空关键词不猜、未知 id 不误删、
 * 以及「过去时间 / 空内容」不允许创建任务。
 */
class ScheduledTaskManagerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = FakeContext()
        // JVM 单测没有 AndroidKeyStore：注入 FakeContext + 明文模式，只验证定时任务业务语义。
        SecretVault.installForTest(context)
    }

    @org.junit.After
    fun tearDown() {
        SecretVault.resetForTest()
    }

    // Test scenario: 空关键词不得取消任何任务，先要求用户查看列表。
    @Test
    fun `empty keyword never cancels a task`() {
        val reply = ScheduledTaskManager.cancel(context, "")

        assertEquals("要取消哪条定时任务？说“查看定时任务”可以先看看列表。", reply)
        assertFalse(reply.contains("已取消"))
    }

    // Test scenario: 纯空白关键词同样视为空。
    @Test
    fun `blank keyword never cancels a task`() {
        val reply = ScheduledTaskManager.cancel(context, "    ")

        assertTrue(reply.startsWith("要取消哪条定时任务？"))
        assertFalse(reply.contains("已取消"))
    }

    // Test scenario: 有内容但匹配不到时明确报未找到，不静默成功。
    @Test
    fun `unknown keyword reports not found`() {
        val reply = ScheduledTaskManager.cancel(context, "播报新闻")

        assertEquals("没有找到要取消的定时任务。", reply)
    }

    // Test scenario: id 不存在时 cancelById 返回 null，UI 可据此提示而不是假装成功。
    @Test
    fun `cancel by unknown id returns null`() {
        assertNull(ScheduledTaskManager.cancelById(context, "st_missing"))
        assertTrue(ScheduledTaskManager.allTasks(context).isEmpty())
    }

    // Test scenario: 删除不存在的任务必须返回 false，调用方不会误以为删掉了。
    @Test
    fun `delete unknown task returns false`() {
        assertFalse(ScheduledTaskManager.deleteTask(context, "st_missing"))
    }

    // Test scenario: 更新不存在的任务返回 null，且不会新建一条。
    @Test
    fun `update unknown task returns null`() {
        assertNull(ScheduledTaskManager.updateTask(context, "st_missing", goal = "播报电量"))
        assertTrue(ScheduledTaskManager.allTasks(context).isEmpty())
    }

    // Test scenario: 空仓库下列表与空任务集合的文案稳定。
    @Test
    fun `empty repository reports no tasks`() {
        assertEquals("现在没有定时任务。", ScheduledTaskManager.list(context))
        assertTrue(ScheduledTaskManager.allTasks(context).isEmpty())
    }

    // Test scenario: 结构化入口拒绝空目标，不建出无法执行的任务。
    @Test
    fun `parsed entry point rejects blank goal`() {
        val at = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(5)

        assertNull(ScheduledTaskManager.createFromParsed(context, "   ", at, daily = false))
        // 参数被拒时不得留下任何任务。
        assertTrue(ScheduledTaskManager.allTasks(context).isEmpty())
    }

    // Test scenario: 结构化入口拒绝过去的时间（闹钟会立即触发或永不触发）。
    @Test
    fun `parsed entry point rejects past time`() {
        assertNull(
            ScheduledTaskManager.createFromParsed(
                context,
                "查一下电量",
                System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(1),
                daily = false,
            ),
        )
        assertTrue(ScheduledTaskManager.allTasks(context).isEmpty())
    }

    // Test scenario: UI 表单入口同样拒绝空白目标。
    @Test
    fun `ui create rejects blank goal`() {
        val at = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(5)

        assertNull(ScheduledTaskManager.createTask(context, "  ", at, daily = false))
        assertTrue(ScheduledTaskManager.allTasks(context).isEmpty())
    }

    // Test scenario: 只有明确的定时播报/查询话术才由本机接管。
    @Test
    fun `handles only scheduled phrases`() {
        assertTrue(ScheduledTaskManager.handles("每天早上8点播报新闻"))
        assertTrue(ScheduledTaskManager.handles("10分钟后执行查看电量"))
        assertFalse(ScheduledTaskManager.handles("打开设置"))
        assertFalse(ScheduledTaskManager.handles("提醒我30分钟后喝水"))
    }

    // Test scenario: 任务模型保留 id/goal/at/daily 四个字段（持久化 schema 不漂移）。
    @Test
    fun `task model keeps id goal time and daily flag`() {
        val task = ScheduledTaskManager.Task(
            id = "st_1",
            goal = "播报天气",
            at = 1_700_000_000_000L,
            daily = true,
        )

        assertEquals("st_1", task.id)
        assertEquals("播报天气", task.goal)
        assertEquals(1_700_000_000_000L, task.at)
        assertTrue(task.daily)
    }

    // Test scenario: 自然语言入口认不出时间时不猜，直接不创建任务。
    @Test
    fun `create without a recognisable time returns null`() {
        assertNull(ScheduledTaskManager.create(context, "帮我播报新闻"))
        assertTrue(ScheduledTaskManager.allTasks(context).isEmpty())
    }

    // Test scenario: 非法的每日钟点（25 点）被拒绝，不会被夹到别的时间。
    @Test
    fun `create rejects an invalid daily hour`() {
        assertNull(ScheduledTaskManager.create(context, "每天25点播报新闻"))
    }

    // Test scenario: 每日任务缺少执行内容时不创建（只有时间等于空任务）。
    @Test
    fun `create rejects a daily task without a goal`() {
        assertNull(ScheduledTaskManager.create(context, "每天早上8点，"))
    }

    // Test scenario: 相对时间必须大于 0 且不超过 30 天，越界一律拒绝。
    @Test
    fun `create rejects out of range relative amounts`() {
        assertNull(ScheduledTaskManager.create(context, "0分钟后查看电量"))
        assertNull(ScheduledTaskManager.create(context, "999分钟后查看电量"))
    }

    // Test scenario: 相对时间缺少执行内容时不创建。
    @Test
    fun `create rejects a relative task without a goal`() {
        assertNull(ScheduledTaskManager.create(context, "10分钟后，"))
        assertTrue(ScheduledTaskManager.allTasks(context).isEmpty())
    }


    // Test scenario: 触发未知 id 直接返回，不打断广播、不改动仓库。
    @Test
    fun `fire with unknown id returns quietly`() {
        ScheduledTaskManager.fire(context, "st_missing")

        assertTrue(ScheduledTaskManager.allTasks(context).isEmpty())
    }

    // Test scenario: 定时任务触发广播的 action/extra 常量属于对外契约。
    @Test
    fun `broadcast constants stay stable`() {
        assertEquals("com.pilot.agent.SCHEDULED_TOOL_FIRE", ScheduledTaskManager.ACTION_FIRE)
        assertEquals("scheduled_tool_id", ScheduledTaskManager.EXTRA_ID)
    }
}