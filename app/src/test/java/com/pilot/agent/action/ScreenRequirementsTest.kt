package com.pilot.agent.action

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 前置条件按命令判定（m-20261127-105）。
 *
 * 不需要屏幕的本地能力不能被无障碍闸门拦住；真要读屏的动作才要求操作权限。
 * 纯文本判定，不碰系统服务。
 */
class ScreenRequirementsTest {

    private fun need(goal: String): ScreenRequirement = ScreenRequirements.requirement(goal)

    // ── 不需要屏幕 ──────────────────────────────────────────────

    @Test
    fun `calendar creation is screenless`() {
        assertEquals(ScreenRequirement.NONE, need("添加日程 明天下午3点项目讨论"))
        assertEquals(ScreenRequirement.NONE, need("取消刚才那个日程"))
    }

    @Test
    fun `reminder and todo are screenless`() {
        assertEquals(ScreenRequirement.NONE, need("提醒我明天上午9点开会"))
        assertEquals(ScreenRequirement.NONE, need("添加待办 买牛奶"))
    }

    @Test
    fun `queries are screenless`() {
        assertEquals(ScreenRequirement.NONE, need("今天上证指数多少"))
        assertEquals(ScreenRequirement.NONE, need("查一下明天北京到上海的高铁"))
    }

    @Test
    fun `volume brightness and dnd are screenless`() {
        assertEquals(ScreenRequirement.NONE, need("音量调到60%"))
        assertEquals(ScreenRequirement.NONE, need("亮度调到30%"))
        assertEquals(ScreenRequirement.NONE, need("关掉勿扰"))
    }

    @Test
    fun `impossible switches are still screenless`() {
        // C 类命令只需要如实拒绝/带路，不必先开无障碍。
        assertEquals(ScreenRequirement.NONE, need("把蓝牙关掉"))
    }

    @Test
    fun `music playback is screenless`() {
        assertEquals(ScreenRequirement.NONE, need("播放周杰伦的晴天"))
        assertEquals(ScreenRequirement.NONE, need("来一首热门歌曲"))
    }

    // ── 需要屏幕 ────────────────────────────────────────────────

    @Test
    fun `opening an in-app page requires screen`() {
        assertEquals(ScreenRequirement.SCREEN, need("打开设置里的电池页面"))
        assertEquals(ScreenRequirement.SCREEN, need("打开微信里的张三聊天"))
        assertEquals(ScreenRequirement.SCREEN, need("在美团里点一份外卖"))
    }

    @Test
    fun `screen tapping requires screen`() {
        assertEquals(ScreenRequirement.SCREEN, need("帮我在支付宝点左上角的扫一扫"))
        assertEquals(ScreenRequirement.SCREEN, need("往下滑看看评论"))
    }
}
