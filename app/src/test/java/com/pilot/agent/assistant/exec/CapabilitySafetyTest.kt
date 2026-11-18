package com.pilot.agent.assistant.exec

import com.pilot.agent.assistant.routing.AssistantRoute
import com.pilot.agent.assistant.routing.RouteDecision
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 安全闸的行为契约（移植自 Minis 的 `LocalAssistantRouter` 逐词校验）。
 *
 * 快路径的每一个参数都必须能追溯到用户自己的原话 —— 模型或规则一旦
 * 编出一个值（占位符、猜出来的路径），这一层就要把它拦下来。
 */
class CapabilitySafetyTest {

    private fun local(
        capability: String,
        action: String,
        args: JSONObject,
    ) = RouteDecision.Local(
        route = AssistantRoute(
            id = "test",
            label = "test",
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

    @Test
    fun `verbatim values must appear in the user text`() {
        assertTrue(CapabilitySafety.isVerbatim("hello", "把 hello 写入 /tmp/a.txt"))
        assertFalse(CapabilitySafety.isVerbatim("world", "把 hello 写入 /tmp/a.txt"))
    }

    @Test
    fun `derived values must be a contiguous slice of the user text`() {
        assertTrue(CapabilitySafety.isContiguousFrom("超市", "附近有超市吗"))
        assertFalse(CapabilitySafety.isContiguousFrom("家乐福", "附近有超市吗"))
        // 真机上 0.6B 的截断输出：「明天会」字都在，但不是「明天开会」的连续片段。
        assertFalse(CapabilitySafety.isContiguousFrom("明天会", "保存到 /sdcard/a.txt 内容 明天开会"))
        assertFalse(CapabilitySafety.isContiguousFrom("明天会议", "保存到 /sdcard/a.txt 内容 明天开会"))
        assertTrue(CapabilitySafety.isContiguousFrom("明天开会", "保存到 /sdcard/a.txt 内容 明天开会"))
    }

    @Test
    fun `a normal file write passes`() {
        val decision = local(
            "file_write",
            "write",
            JSONObject()
                .put("path", "/sdcard/Download/a.txt")
                .put("content", "hello"),
        )
        assertEquals(
            CapabilitySafety.Verdict.Allow,
            CapabilitySafety.check(decision, "把 hello 写入 /sdcard/Download/a.txt"),
        )
    }

    @Test
    fun `a path that is not in the user text is rejected`() {
        val decision = local(
            "file_write",
            "write",
            JSONObject()
                .put("path", "/etc/hosts")
                .put("content", "hello"),
        )
        val verdict = CapabilitySafety.check(decision, "把 hello 写入 /sdcard/Download/a.txt")
        assertTrue(verdict is CapabilitySafety.Verdict.Deny)
    }

    @Test
    fun `file write without a path or content is rejected`() {
        val noPath = local("file_write", "write", JSONObject().put("content", "hello"))
        assertTrue(CapabilitySafety.check(noPath, "把 hello 写入文件") is CapabilitySafety.Verdict.Deny)

        val noContent = local(
            "file_write",
            "write",
            JSONObject().put("path", "/tmp/a.txt"),
        )
        assertTrue(CapabilitySafety.check(noContent, "写入 /tmp/a.txt") is CapabilitySafety.Verdict.Deny)
    }

    @Test
    fun `an out of range volume is rejected`() {
        val decision = local("volume", "adjust", JSONObject().put("percent", 500))
        assertTrue(CapabilitySafety.check(decision, "调大音量") is CapabilitySafety.Verdict.Deny)
    }

    @Test
    fun `slots waiting for the on-device model may be empty`() {
        // 模型补值前：content 还没抽出来，但它在 pending 里 —— 允许通过。
        val decision = local(
            "file_write",
            "write",
            JSONObject().put("path", "/sdcard/Download/a.txt"),
        )
        assertEquals(
            CapabilitySafety.Verdict.Allow,
            CapabilitySafety.check(
                decision,
                "保存到 /sdcard/Download/a.txt 内容 hello",
                pending = setOf("content"),
            ),
        )
        // 补完再跑完整校验：content 仍然空 → 拒绝。
        assertTrue(
            CapabilitySafety.check(decision, "保存到 /sdcard/Download/a.txt 内容 hello")
                is CapabilitySafety.Verdict.Deny,
        )
    }

    @Test
    fun `destructive capabilities always require confirmation`() {
        val decision = local(
            "file_write",
            "write",
            JSONObject()
                .put("path", "/tmp/a.txt")
                .put("content", "hello"),
        )
        assertTrue(CapabilitySafety.requiresConfirmation(decision))
    }
}
