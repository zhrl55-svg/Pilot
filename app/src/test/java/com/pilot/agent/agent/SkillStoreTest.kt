package com.pilot.agent.agent

import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 技能库匹配与沉淀（审核 S-01~S-07）。
 *
 * - 同目标能命中；反义动词（打开/关闭）槽位不一致必须拒绝；
 * - 含资金/删除等风险词的目标不沉淀，也不能被匹配；
 * - 连续回放失败达到 [SkillStore.MAX_FAILURES] 即淘汰。
 */
class SkillStoreTest {

    private fun newStore(): SkillStore =
        SkillStore(Files.createTempDirectory("skills").toFile())

    private val steps = listOf(SkillStep(action = "tap_text", label = "蓝牙"))

    @Test
    fun `records and matches the same goal`() {
        val store = newStore()
        assertTrue(store.recordSuccess("打开蓝牙", "com.android.settings", "com.android.settings", steps))
        assertNotNull(store.match("打开蓝牙", "com.android.settings"))
    }

    @Test
    fun `antonym slot is rejected`() {
        val store = newStore()
        store.recordSuccess("打开蓝牙", "com.android.settings", "com.android.settings", steps)
        // 「关闭」与「打开」是反义槽位：绝不能命中「打开蓝牙」的技能
        assertNull(store.match("关闭蓝牙", "com.android.settings"))
    }

    @Test
    fun `risky goal is not persisted`() {
        val store = newStore()
        val riskySteps = listOf(SkillStep(action = "tap_text", label = "确认支付"))
        assertFalse(store.recordSuccess("给对方转账", "com.tencent.mm", "com.tencent.mm", riskySteps))
        assertNull(store.match("给对方转账", "com.tencent.mm"))
    }

    @Test
    fun `consecutive replay failures eliminate the skill`() {
        val store = newStore()
        store.recordSuccess("打开蓝牙", "com.android.settings", "com.android.settings", steps)
        val hit = store.match("打开蓝牙", "com.android.settings")
        assertNotNull(hit)
        repeat(SkillStore.MAX_FAILURES) { store.recordFailure(hit!!.id) }
        assertNull(store.match("打开蓝牙", "com.android.settings"))
    }

    @Test
    fun `unrelated goal does not match`() {
        val store = newStore()
        store.recordSuccess("打开蓝牙", "com.android.settings", "com.android.settings", steps)
        assertNull(store.match("播放一首歌", "com.android.settings"))
    }
}
