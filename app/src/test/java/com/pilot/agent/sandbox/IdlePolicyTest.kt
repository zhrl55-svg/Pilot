package com.pilot.agent.sandbox

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 沙箱空闲回收判定（审核 code R1）。
 *
 * 关键契约：有命令在跑时**永不**回收；阈值 Long.MAX_VALUE 表示关闭回收。
 * 旧实现会误杀正在执行的长命令。
 */
class IdlePolicyTest {

    @Test
    fun `never recycle while a command is in flight`() {
        assertFalse(IdlePolicy.shouldRecycle(inFlight = 1, lastActiveAt = 0L, now = 100_000L, thresholdMs = 1_000L))
    }

    @Test
    fun `recycle after the idle threshold`() {
        assertTrue(IdlePolicy.shouldRecycle(inFlight = 0, lastActiveAt = 0L, now = 10_000L, thresholdMs = 5_000L))
    }

    @Test
    fun `not recycled before the threshold`() {
        assertFalse(IdlePolicy.shouldRecycle(inFlight = 0, lastActiveAt = 9_000L, now = 10_000L, thresholdMs = 5_000L))
    }

    @Test
    fun `disabled threshold never recycles`() {
        assertFalse(
            IdlePolicy.shouldRecycle(
                inFlight = 0,
                lastActiveAt = 0L,
                now = Long.MAX_VALUE / 2,
                thresholdMs = Long.MAX_VALUE,
            ),
        )
    }
}
