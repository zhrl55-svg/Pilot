package com.pilot.agent.tool

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 遗留匹配器的讨论/疑问闸。
 *
 * 回归背景：遗留截图匹配器只看「句子里有没有"截图"」，
 * 「截图功能怎么实现」会真的截一张图；设备匹配器会把
 * 「我们可以加个电量显示功能」当成查电量。
 */
class LegacyIntentGuardTest {

    @Test
    fun `a feature request is a discussion`() {
        assertTrue(LegacyIntentGuard.isDiscussion("我们可以加个电量显示功能"))
        assertTrue(LegacyIntentGuard.isDiscussion("讨论一下截屏的原理"))
        assertTrue(LegacyIntentGuard.isDiscussion("怎么实现记忆功能"))
    }

    @Test
    fun `a plain status question is neither`() {
        assertFalse(LegacyIntentGuard.isDiscussion("电量还剩多少"))
        assertFalse(LegacyIntentGuard.isDiscussion("蓝牙状态"))
        assertFalse(LegacyIntentGuard.isQuestion("电量还剩多少"))
    }

    @Test
    fun `a question is a question`() {
        assertTrue(LegacyIntentGuard.isQuestion("网络怎么样"))
        assertTrue(LegacyIntentGuard.isQuestion("怎么截图"))
        assertTrue(LegacyIntentGuard.isQuestion("为什么蓝牙连不上"))
    }

    @Test
    fun `commands are not blocked`() {
        listOf("截个屏", "截图", "帮我截屏", "截屏一下", "电量还剩多少", "打开设置")
            .forEach {
                assertFalse("「$it」是命令", LegacyIntentGuard.isQuestionOrDiscussion(it))
                assertFalse("「$it」是命令", LegacyIntentGuard.isDiscussion(it))
            }
    }

    @Test
    fun `only path-like payloads are treated as paths`() {
        assertTrue(LegacyIntentGuard.looksLikePath("/sdcard/Download/a.txt"))
        assertTrue(LegacyIntentGuard.looksLikePath("a.txt"))
        assertTrue(LegacyIntentGuard.looksLikePath("“a.md”"))
        assertFalse(LegacyIntentGuard.looksLikePath("的方案"))
        assertFalse(LegacyIntentGuard.looksLikePath("文件"))
        assertFalse(LegacyIntentGuard.looksLikePath(""))
    }
}
