package com.pilot.agent.agent

import com.pilot.agent.perception.ScreenSnapshot
import com.pilot.agent.perception.UiNode
import com.pilot.agent.testutil.FakeContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 护栏判定（审核 G-01~G-06 / P0-2）。
 *
 * 这些用例锁住几条最容易被改坏的行为：
 * - mcp_call / script_run 一律确认（不再按工具名黑名单猜）；
 * - 终端命令白名单放行、危险命令强确认、读过外部内容后只读命令也要确认；
 * - 「确定」这类通用按钮要结合所在页面语境判定（确认支付 → 资金风险）；
 * - 密码框输入判敏感。
 *
 * 全部是纯 JVM 断言：assess 只读 action 与快照，不碰系统服务。
 */
class GuardTest {

    private val guard = Guard(FakeContext())
    private val empty = ScreenSnapshot()

    @Test
    fun `mcp call always needs confirmation`() {
        val action = AgentAction(action = "mcp_call", server = "fs", tool = "write_file")
        assertEquals(Risk.EXTERNAL, guard.riskOf(action, empty))
    }

    @Test
    fun `script run always needs confirmation`() {
        val action = AgentAction(action = "script_run", target = "deploy")
        assertEquals(Risk.EXTERNAL, guard.riskOf(action, empty))
    }

    @Test
    fun `read only terminal command passes`() {
        val action = AgentAction(action = "systemop", target = "terminal", text = "ls -la /sdcard")
        assertEquals(Risk.NONE, guard.riskOf(action, empty))
    }

    @Test
    fun `dangerous terminal command is strongly confirmed`() {
        val action = AgentAction(action = "systemop", target = "terminal", text = "rm -rf /data")
        val assessment = guard.assess(action, empty)
        assertEquals(Risk.IRREVERSIBLE, assessment.risk)
        assertTrue("危险命令要走强确认（连续两次）", assessment.strong)
    }

    @Test
    fun `tainted read only terminal still asks`() {
        val action = AgentAction(action = "systemop", target = "terminal", text = "cat note.txt")
        // 未污染：只读放行
        assertEquals(Risk.NONE, guard.assess(action, empty, tainted = false).risk)
        // 已把外部内容喂给规划器：只读命令也要确认，防提示注入链
        assertEquals(Risk.EXTERNAL, guard.assess(action, empty, tainted = true).risk)
    }

    @Test
    fun `confirm button inside pay dialog is money risk`() {
        val snap = ScreenSnapshot(
            pkg = "com.tencent.mm",
            nodes = listOf(
                UiNode(id = 0, text = "确定", clickable = true),
                UiNode(id = 1, text = "取消", clickable = true),
            ),
            contextText = "确认支付 ¥299？",
        )
        assertEquals(Risk.MONEY, guard.riskOf(AgentAction(action = "tap_node", id = 0), snap))
    }

    @Test
    fun `password field input is sensitive`() {
        val snap = ScreenSnapshot(nodes = listOf(UiNode(id = 0, editable = true, password = true)))
        assertEquals(
            Risk.SENSITIVE,
            guard.riskOf(AgentAction(action = "input", id = 0, text = "123456"), snap),
        )
    }

    @Test
    fun `plain open is safe`() {
        assertEquals(Risk.NONE, guard.riskOf(AgentAction(action = "open", target = "com.tencent.mm"), empty))
    }
}
