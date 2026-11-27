package com.pilot.agent.assistant.exec

import android.content.Context
import com.pilot.agent.assistant.routing.AssistantRoute
import com.pilot.agent.assistant.routing.RouteDecision
import com.pilot.agent.query.SecretVault
import com.pilot.agent.testutil.FakeContext
import com.pilot.agent.tool.LocalToolRuntime
import com.pilot.agent.tool.ToolConfirmer
import com.pilot.agent.tool.ToolGroup
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * file_write 的覆盖语义契约。
 *
 * P1 之前固定 overwrite=true，任何一次写入都可能悄悄替换掉同名文件。
 * 现在只有用户原话里明确说「覆盖 / overwrite」才允许替换，其余一律走
 * 「同名文件存在时会停止」的确认文案 —— 这两条文案就是执行层要遵守的契约，
 * 一旦有人改回固定 true，这里的断言会立刻失败。
 */
class CapabilityExecutorTest {

    private lateinit var context: Context
    private lateinit var tools: LocalToolRuntime
    private lateinit var executor: CapabilityExecutor

    @Before
    fun setUp() {
        context = FakeContext()
        // JVM 单测没有 AndroidKeyStore：注入 FakeContext + 明文模式，只验证能力执行语义。
        SecretVault.installForTest(context)
        tools = LocalToolRuntime(context) { null }
        executor = CapabilityExecutor(context = context, tools = tools)
    }

    @org.junit.After
    fun tearDown() {
        SecretVault.resetForTest()
    }

    private fun localDecision(
        capability: String,
        action: String,
        args: JSONObject = JSONObject(),
    ) = RouteDecision.Local(
        route = AssistantRoute(
            id = "test.$capability",
            label = "测试",
            capability = capability,
            action = action,
        ),
        capability = capability,
        action = action,
        args = args,
        needsConfirmation = true,
        needsModelExtraction = false,
        missingSlots = emptySet(),
        score = 100,
    )

    private fun fileWriteDecision(path: String = "/tmp/a.txt", content: String = "hello") =
        localDecision(
            "file_write",
            "write",
            JSONObject().put("path", path).put("content", content),
        )

    // Test scenario: 用户没说「覆盖」时，确认文案必须提示同名文件会停止，不能默认替换。
    @Test
    fun `plain write asks for confirmation without overwrite`() {
        val invocation = executor.invocation(
            fileWriteDecision(),
            "把 hello 写入 /tmp/a.txt",
        )

        assertEquals("write_file", invocation!!.name)
        assertEquals(ToolGroup.WRITE, invocation.group)
        assertEquals("写入 /tmp/a.txt", invocation.summary)
        assertEquals("确认把内容写入「/tmp/a.txt」吗？同名文件存在时会停止。", invocation.confirmation)
        assertFalse(invocation.confirmation.contains("确认覆盖"))
    }

    // Test scenario: 用户明确说「覆盖」时，确认文案必须切换成覆盖确认。
    @Test
    fun `explicit overwrite wording switches to overwrite confirmation`() {
        val invocation = executor.invocation(
            fileWriteDecision(),
            "把 hello 覆盖写入 /tmp/a.txt",
        )

        assertEquals("确认覆盖「/tmp/a.txt」吗？", invocation!!.confirmation)
        assertFalse(invocation.confirmation.contains("同名文件存在时会停止"))
    }

    // Test scenario: 英文 overwrite 同样视为明确覆盖意图（大小写不敏感）。
    @Test
    fun `english overwrite keyword is honoured case insensitively`() {
        val invocation = executor.invocation(
            fileWriteDecision(),
            "please OVERWRITE /tmp/a.txt with hello",
        )

        assertEquals("确认覆盖「/tmp/a.txt」吗？", invocation!!.confirmation)
    }

    // Test scenario: 覆盖确认文案必须指名目标路径，避免用户确认错文件。
    @Test
    fun `overwrite confirmation names the target path`() {
        val invocation = executor.invocation(
            fileWriteDecision(path = "/sdcard/Download/note.txt"),
            "把 hello 覆盖写入 /sdcard/Download/note.txt",
        )

        assertEquals("确认覆盖「/sdcard/Download/note.txt」吗？", invocation!!.confirmation)
        assertTrue(invocation.confirmation.contains("/sdcard/Download/note.txt"))
    }

    // Test scenario: 缺少路径时不构造调用，交回原链路，绝不猜一个路径去写。
    @Test
    fun `blank path yields no invocation`() {
        val invocation = executor.invocation(
            fileWriteDecision(path = "   "),
            "把 hello 写入文件",
        )

        assertNull(invocation)
    }

    // Test scenario: 内容为空时不构造调用（空写会把已有文件内容抹掉）。
    @Test
    fun `blank content yields no invocation`() {
        val invocation = executor.invocation(
            fileWriteDecision(content = ""),
            "把内容写入 /tmp/a.txt",
        )

        assertNull(invocation)
    }

    // Test scenario: 未知能力返回 null，不静默落到文件写入。
    @Test
    fun `unknown capability is not routed to file write`() {
        val decision = fileWriteDecision().copy(capability = "unknown_capability", action = "write")

        assertNull(executor.invocation(decision, "把 hello 覆盖写入 /tmp/a.txt"))
    }

    // Test scenario: overwrite 标志真的传到了文件层 —— 没说「覆盖」时同名文件保留原内容，
    // 明确说「覆盖」后才替换。断言落盘内容，改回固定 overwrite=true 会立刻失败。
    @Test
    fun `execute honours overwrite flag derived from user wording`() = runBlocking {
        val path = "~/notes/note.txt"
        val target = File(context.filesDir, "notes/note.txt").canonicalFile
        val confirmAll = ToolConfirmer { _, _ -> true }

        val first = executor.invocation(fileWriteDecision(path = path, content = "one"), "把 one 写入 $path")!!
            .execute(context, confirmAll)
        assertEquals("已写入 ${target.absolutePath}（3 个字符）。", first.message)
        assertEquals("one", target.readText())

        val second = executor.invocation(fileWriteDecision(path = path, content = "two"), "把 two 写入 $path")!!
            .execute(context, confirmAll)
        assertEquals("同名文件已经存在。若要替换，请在指令里明确说“覆盖”。", second.message)
        assertEquals("one", target.readText())

        val third = executor.invocation(fileWriteDecision(path = path, content = "two"), "把 two 覆盖写入 $path")!!
            .execute(context, confirmAll)
        assertEquals("已写入 ${target.absolutePath}（3 个字符）。", third.message)
        assertEquals("two", target.readText())
    }

    // Test scenario: 音量能力把抽取到的百分比带进文案；缺参数时不构造调用。
    @Test
    fun `volume capability carries the requested percent`() {
        val set = executor.invocation(
            localDecision("volume", "set", JSONObject().put("percent", 40)),
            "把音量调到 40%",
        )!!
        assertEquals("set_volume", set.name)
        assertEquals(ToolGroup.READ, set.group)
        assertTrue(set.summary.endsWith("设为 40%"))
        assertTrue(set.confirmation.contains("40%"))

        assertNull(executor.invocation(localDecision("volume", "set"), "调音量"))
        assertNull(
            executor.invocation(
                localDecision("volume", "adjust", JSONObject().put("percent", 0)),
                "调音量",
            ),
        )
    }

    // Test scenario: 未知动作不被猜成某个具体能力，一律交回原链路。
    @Test
    fun `unknown actions are not guessed`() {
        assertNull(executor.invocation(localDecision("volume", "mute"), "静音"))
        assertNull(executor.invocation(localDecision("media", "shuffle"), "随机播放"))
        assertNull(executor.invocation(localDecision("device", "cpu"), "查看 CPU"))
        assertNull(executor.invocation(localDecision("reminder", "snooze"), "稍后再提醒"))
        assertNull(executor.invocation(localDecision("scheduled_task", "pause"), "暂停任务"))
        assertNull(executor.invocation(localDecision("memory_get", "forget"), "忘掉"))
    }

    // Test scenario: 只读能力都落到已有的本地工具名上，不新造执行路径。
    @Test
    fun `read only capabilities map to existing local tools`() {
        assertEquals("device_battery", executor.invocation(localDecision("device", "battery"), "查电量")!!.name)
        assertEquals("device_storage", executor.invocation(localDecision("device", "storage"), "查存储")!!.name)
        assertEquals("media_pause", executor.invocation(localDecision("media", "pause"), "暂停")!!.name)
        assertEquals("media_next", executor.invocation(localDecision("media", "next"), "下一首")!!.name)
        assertEquals("take_screenshot", executor.invocation(localDecision("screenshot", "capture"), "截屏")!!.name)
    }

    // Test scenario: 取消类动作必须进 DELETE 组（默认每次确认），列表/创建分组不漂移。
    @Test
    fun `cancel capabilities stay in the delete group`() {
        val reminderCancel = executor.invocation(localDecision("reminder", "cancel"), "取消提醒 开会")!!
        assertEquals("cancel_reminder", reminderCancel.name)
        assertEquals(ToolGroup.DELETE, reminderCancel.group)

        val taskCancel = executor.invocation(localDecision("scheduled_task", "cancel"), "取消定时任务 播报")!!
        assertEquals("cancel_scheduled_task", taskCancel.name)
        assertEquals(ToolGroup.DELETE, taskCancel.group)

        val reminderList = executor.invocation(localDecision("reminder", "list"), "我的提醒")!!
        assertEquals("list_reminders", reminderList.name)
        assertEquals(ToolGroup.READ, reminderList.group)

        val taskCreate = executor.invocation(localDecision("scheduled_task", "create"), "每天8点播报新闻")!!
        assertEquals("create_scheduled_task", taskCreate.name)
        assertEquals(ToolGroup.WRITE, taskCreate.group)
    }

    // Test scenario: 记忆写入必须有内容，空内容不构造调用。
    @Test
    fun `memory write requires content`() {
        val write = executor.invocation(
            localDecision("memory_write", "remember", JSONObject().put("content", "我对花生过敏")),
            "记住我对花生过敏",
        )!!
        assertEquals("memory_remember", write.name)
        assertEquals(ToolGroup.WRITE, write.group)

        assertNull(executor.invocation(localDecision("memory_write", "remember"), "记住"))
    }

    // Test scenario: 读文件同样要求路径，且落在 READ 组。
    @Test
    fun `file read requires a path`() {
        val read = executor.invocation(
            localDecision("file_read", "read", JSONObject().put("path", "/sdcard/a.txt")),
            "读取 /sdcard/a.txt",
        )!!
        assertEquals("read_file", read.name)
        assertEquals(ToolGroup.READ, read.group)
        assertEquals("读取 /sdcard/a.txt", read.summary)

        assertNull(executor.invocation(localDecision("file_read", "read"), "读文件"))
    }

    // Test scenario: 打不开的应用交回原链路，而不是回一句「没找到」把请求做死。
    @Test
    fun `unknown app is handed back to the legacy chain`() {
        val decision = localDecision("app_launch", "open", JSONObject().put("app", "不存在的应用xyz"))

        assertNull(executor.invocation(decision, "打开 不存在的应用xyz"))
    }

    // Test scenario: 提醒的列表/取消能力真的走到 ReminderManager，而不是只构造文案。
    @Test
    fun `reminder list and cancel execute against the manager`() = runBlocking {
        val confirmAll = ToolConfirmer { _, _ -> true }

        val list = executor.invocation(localDecision("reminder", "list"), "我的提醒")!!
            .execute(context, confirmAll)
        assertEquals("现在没有待触发的提醒。", list.message)

        val cancel = executor.invocation(localDecision("reminder", "cancel"), "取消提醒 开会")!!
            .execute(context, confirmAll)
        assertEquals("没有找到要取消的提醒。说“我的提醒”可以查看列表。", cancel.message)
    }

    // Test scenario: 定时任务的列表/未知 id 取消同样落到管理器，并明确报未找到。
    @Test
    fun `scheduled task list and cancel execute against the manager`() = runBlocking {
        val confirmAll = ToolConfirmer { _, _ -> true }

        val list = executor.invocation(localDecision("scheduled_task", "list"), "查看定时任务")!!
            .execute(context, confirmAll)
        assertEquals("现在没有定时任务。", list.message)

        val cancel = executor.invocation(
            localDecision("scheduled_task", "cancel", JSONObject().put("id", "st_missing")),
            "取消定时任务",
        )!!.execute(context, confirmAll)
        assertEquals("没有找到要取消的定时任务。", cancel.message)
    }

    // Test scenario: 白名单外的路径被拒绝，执行层不会越界读文件。
    @Test
    fun `file read outside the sandbox is denied`() = runBlocking {
        val result = executor.invocation(
            localDecision("file_read", "read", JSONObject().put("path", "/etc/hosts")),
            "读取 /etc/hosts",
        )!!.execute(context, ToolConfirmer { _, _ -> true })

        assertTrue(result.message.startsWith("这个路径不在 Pilot 可访问的范围内"))
    }
}
