package com.pilot.agent.action

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 直达目录的「这到底是不是指令」判定。
 *
 * 回归背景（与 Minis 的「系统设置」误命中同源）：`resolve` 曾经是
 * 「整句含任一意图词 + 整句含任一目标词」，而意图词里连「设置」「查看」
 * 都有 —— 「你测试一下系统设置执行生效了没有」既含「设置」又含
 * 「系统设置」，真的打开了设置页。用户说的明明是另一件事。
 *
 * 设置页是给别人看的界面，不该因为一句话里**顺带提到**软件功能就弹出来。
 * 判不准的一律返回 null 交回原链路（后面还有 App 索引、设置搜索、云端模型）。
 */
class ActionCatalogIntentTest {

    private fun labelOf(goal: String): String? = ActionCatalog.resolve(goal)?.label

    @Test
    fun `explicit open commands still resolve`() {
        val cases = mapOf(
            "打开设置" to "设置",
            "打开设置页" to "设置",
            "打开系统设置" to "设置",
            "进入系统设置" to "设置",
            "帮我打开系统设置" to "设置",
            "设置主页" to "设置",
            "打开蓝牙设置" to "蓝牙",
            "打开 WiFi 设置" to "WLAN",
            "打开wifi" to "WLAN",
            "打开电池" to "电池用量",
            "把蓝牙打开" to "蓝牙",
            "请打开通知" to "通知",
            "打开应用管理" to "应用",
            "看看存储空间" to "存储",
            "open bluetooth settings" to "蓝牙",
            "open wifi settings" to "WLAN",
        )
        cases.forEach { (goal, expected) ->
            assertEquals("「$goal」应当直达「$expected」", expected, labelOf(goal))
        }
    }

    @Test
    fun `topic mentions are not commands`() {
        val cases = listOf(
            "你测试一下系统设置执行生效了没有",
            "系统设置里有这个选项吗",
            "系统设置这个词经常出现在日志里",
            "我上次打开系统设置的时候发现一个问题",
            "打开设置的时候页面卡了一下",
            "通知栏那个图标你帮我看看",
            "电池续航变差了怎么办",
            "蓝牙怎么打开",
        )
        cases.forEach { goal ->
            assertNull("「$goal」只是在提及，不该直达任何页面", labelOf(goal))
        }
    }

    @Test
    fun `resolveTarget keeps working for an already-extracted app slot`() {
        // CapabilityExecutor 拿到的 app 槽可能就是「系统设置」这种纯名词，
        // 用户已经说过「打开」，这里不能再要求意图动词。
        assertEquals("设置", ActionCatalog.resolveTarget("系统设置")?.label)
        assertEquals("电池用量", ActionCatalog.resolveTarget("电池用量")?.label)
        assertEquals("蓝牙", ActionCatalog.resolveTarget("蓝牙")?.label)
        // 普通 App 名不在目录里，交给 App 索引去解析。
        assertNull(ActionCatalog.resolveTarget("计算器"))
    }
}
