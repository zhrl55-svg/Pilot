package com.pilot.agent.action

import android.content.Context
import com.pilot.agent.testutil.FakeContext
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * 提醒时间解析/校验（审核 P0-7 提醒相关）。
 *
 * 这里锁定 [ReminderManager.createFromParsed] 的时间窗口契约：
 * 必须落在「现在之后、未来 30 天以内」，否则给出明确中文提示、不写入；
 * 标题为空同样拒绝。
 *
 * 说明：无参的私有 parse()（"N分钟后"自然语言）走同一套时间窗口校验，
 * 端到端解析留给真机验证；此处覆盖窗口边界与空标题这两条纯逻辑分支。
 */
class ReminderTimeTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = FakeContext()
    }

    @Test
    fun `past time is rejected`() {
        val past = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(1)
        assertEquals(
            "提醒时间需要在现在之后、未来 30 天以内。",
            ReminderManager.createFromParsed(context, "喝水", past),
        )
    }

    @Test
    fun `beyond thirty days is rejected`() {
        val far = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(31)
        assertEquals(
            "提醒时间需要在现在之后、未来 30 天以内。",
            ReminderManager.createFromParsed(context, "开会", far),
        )
    }

    @Test
    fun `blank title is rejected`() {
        val soon = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(5)
        assertNull(ReminderManager.createFromParsed(context, "   ", soon))
    }
}
