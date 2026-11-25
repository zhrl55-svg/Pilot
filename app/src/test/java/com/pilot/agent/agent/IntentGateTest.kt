package com.pilot.agent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 后台路由的关键词兜底契约。
 *
 * 后台（广播/语音，无界面）链路拿不到云端结论时，靠 [IntentGate.classify]
 * 决定「回答 / 查询 / 执行」。它一旦把闲聊判成任务，就会在无人值守时乱点屏幕；
 * 把操作判成闲聊，又会让用户的指令石沉大海。这里锁定三分类的边界。
 */
class IntentGateTest {

    // Test scenario: 空输入不执行任何设备操作。
    @Test
    fun `blank input is chat`() {
        assertEquals(IntentGate.Kind.CHAT, IntentGate.classify(""))
        assertEquals(IntentGate.Kind.CHAT, IntentGate.classify("   "))
    }

    // Test scenario: 问助手自己的问题交给模型回答，不能被规则当成查询或任务。
    @Test
    fun `self questions are chat`() {
        assertEquals(IntentGate.Kind.CHAT, IntentGate.classify("你是谁"))
        assertEquals(IntentGate.Kind.CHAT, IntentGate.classify("你能做什么"))
        assertEquals(IntentGate.Kind.CHAT, IntentGate.classify("你的长期记忆是什么"))
    }

    // Test scenario: 明确的设备操作动词判为任务，后台才会进入执行链路。
    @Test
    fun `device actions are tasks`() {
        assertEquals(IntentGate.Kind.TASK, IntentGate.classify("打开设置"))
        assertEquals(IntentGate.Kind.TASK, IntentGate.classify("把音量调到 50%"))
        assertEquals(IntentGate.Kind.TASK, IntentGate.classify("提醒我30分钟后喝水"))
        assertEquals(IntentGate.Kind.TASK, IntentGate.classify("叫我起床"))
    }

    // Test scenario: 句子里提到「系统设置」但没有打开动作，是在核对/提问，
    // 不能因为 TASK_VERBS 里裸的「设置」就判成任务（真机会去打开设置页）。
    @Test
    fun `settings mentions without an open verb are chat`() {
        assertEquals(IntentGate.Kind.CHAT, IntentGate.classify("你测试一下系统设置执行生效了没有"))
        assertEquals(IntentGate.Kind.CHAT, IntentGate.classify("系统设置里有这个选项吗"))
        assertEquals(IntentGate.Kind.CHAT, IntentGate.classify("设置页那个开关是什么意思"))
        // 带打开动作的仍然是任务
        assertEquals(IntentGate.Kind.TASK, IntentGate.classify("打开系统设置"))
        assertEquals(IntentGate.Kind.TASK, IntentGate.classify("进入设置页"))
    }

    // Test scenario: 资讯/行情类问题判为查询，走数据源而不是动手。
    @Test
    fun `information requests are queries`() {
        assertEquals(IntentGate.Kind.QUERY, IntentGate.classify("查一下上证指数"))
        assertEquals(IntentGate.Kind.QUERY, IntentGate.classify("今天天气怎么样"))
        assertEquals(IntentGate.Kind.QUERY, IntentGate.classify("帮我看看几点了"))
    }

    // Test scenario: isTask 与 classify 保持一致（后台路由只用 isTask 做快判）。
    @Test
    fun `isTask agrees with classify`() {
        assertTrue(IntentGate.isTask("打开微信"))
        assertFalse(IntentGate.isTask("你是谁"))
        assertFalse(IntentGate.isTask("查一下上证指数"))
    }

    // Test scenario: reason 把三类意图翻译成可读解释，便于日志排查误判。
    @Test
    fun `reason explains the classification`() {
        assertEquals("空输入", IntentGate.reason("  "))
        assertEquals("操作指令", IntentGate.reason("打开设置"))
        assertEquals("信息查询", IntentGate.reason("查一下上证指数"))
        assertEquals("看起来是交流而不是操作指令", IntentGate.reason("你是谁"))
    }

    // Test scenario: 免打扰话术命中后应让助手停止聆听。
    @Test
    fun `do not disturb phrases are recognised`() {
        assertTrue(IntentGate.isDoNotDisturb("我先忙一会"))
        assertTrue(IntentGate.isDoNotDisturb("别打扰我"))
        assertTrue(IntentGate.isDoNotDisturb("你先歇着吧"))
    }

    // Test scenario: 同一句里带明确设备动作时不算免打扰，避免吞掉真实指令。
    @Test
    fun `do not disturb does not swallow device commands`() {
        assertFalse(IntentGate.isDoNotDisturb("别打扰我，播放音乐"))
        assertFalse(IntentGate.isDoNotDisturb("我先忙完工作再打开设置"))
        assertFalse(IntentGate.isDoNotDisturb(""))
    }
}