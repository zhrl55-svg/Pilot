package com.pilot.agent.action

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 通道无关的统一路由（m-20261127-106）。
 *
 * UI / 后台语音 / 定时任务 / 调试入口都调 [ScreenRequirements.route]；
 * 这里把「无障碍关掉时应该怎么走」固化下来，防止某个通道又写一套老逻辑。
 */
class BackgroundRouteTest {

    private fun route(goal: String, screenReady: Boolean = false): CommandRoute =
        ScreenRequirements.route(goal, screenReady)

    @Test
    fun `stock query runs locally without screen`() {
        assertEquals(CommandRoute.LOCAL, route("今天上证指数多少"))
    }

    @Test
    fun `reminder calendar todo and volume run locally without screen`() {
        assertEquals(CommandRoute.LOCAL, route("提醒我明天上午9点开会"))
        assertEquals(CommandRoute.LOCAL, route("添加日程 明天下午3点项目讨论"))
        assertEquals(CommandRoute.LOCAL, route("添加待办 买牛奶"))
        assertEquals(CommandRoute.LOCAL, route("音量调到60%"))
    }

    @Test
    fun `music runs locally without screen`() {
        assertEquals(CommandRoute.LOCAL, route("播放周杰伦的晴天"))
    }

    @Test
    fun `impossible is distinguished from missing permission`() {
        // C 类：命令本身做不到，与无障碍无关。
        assertEquals(CommandRoute.IMPOSSIBLE, route("把蓝牙关掉"))
        assertEquals(CommandRoute.IMPOSSIBLE, route("把蓝牙关掉", screenReady = true))
    }

    @Test
    fun `screen action is refused only when screen is not ready`() {
        assertEquals(CommandRoute.NEEDS_SCREEN, route("在微信里点开和张三的聊天"))
        assertEquals(CommandRoute.AGENT, route("在微信里点开和张三的聊天", screenReady = true))
    }

    @Test
    fun `screenless commands never depend on accessibility`() {
        // 同一个命令，无障碍开/关都必须走本机，不能因为缺权限被拦。
        listOf("今天上证指数多少", "提醒我明天上午9点开会", "音量调到60%").forEach {
            assertEquals("goal=$it", CommandRoute.LOCAL, route(it, screenReady = false))
            assertEquals("goal=$it", CommandRoute.LOCAL, route(it, screenReady = true))
        }
    }

    @Test
    fun `c class background text does not ask a question nobody can answer`() {
        val cmd = SystemCommand(
            CommandKind.IMPOSSIBLE,
            CommandTarget.BLUETOOTH,
            on = false,
            reason = "Android 13 起没有公开的蓝牙开关接口",
        )
        val background = CommandSemantics.needsUserText(cmd, askGuide = false)
        org.junit.Assert.assertFalse(background.contains("要我带你"))
        org.junit.Assert.assertTrue(background.contains("解锁后在界面里"))
        // 前台默认行为不变。
        org.junit.Assert.assertTrue(CommandSemantics.needsUserText(cmd).contains("要我带你"))
    }
}
