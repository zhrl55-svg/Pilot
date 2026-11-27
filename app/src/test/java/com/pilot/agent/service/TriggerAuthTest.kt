package com.pilot.agent.service

import com.pilot.agent.testutil.FakeContext
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 外部触发调用方校验（审核 code.md S1）。
 *
 * 这里只覆盖不需要真实 PackageManager 的纯判定：系统没给 UID / 非法 UID 一律判为
 * [TriggerAuth.Caller.Unknown]（只依赖 manifest 的 signature 权限），以及 label 文案。
 * 真正的签名比对需要真机包管理器，放到真机验证清单。
 */
class TriggerAuthTest {

    private val ctx = FakeContext()

    @Test
    fun `invalid uid is unknown not trusted`() {
        // android.os.Process.INVALID_UID == -1
        assertEquals(TriggerAuth.Caller.Unknown, TriggerAuth.classify(ctx, -1))
    }

    @Test
    fun `negative uid is unknown`() {
        assertEquals(TriggerAuth.Caller.Unknown, TriggerAuth.classify(ctx, -42))
    }

    @Test
    fun `label formats each caller kind`() {
        assertEquals("uid=1000", TriggerAuth.label(TriggerAuth.Caller.Verified(1000)))
        assertEquals("rejected uid=999", TriggerAuth.label(TriggerAuth.Caller.Rejected(999)))
        assertEquals("perm-gated", TriggerAuth.label(TriggerAuth.Caller.Unknown))
    }
}
