package com.pilot.agent.display

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 虚拟屏能力探测：普通安装（缺 ADD_TRUSTED_DISPLAY）必须不可用，安全退回。 */
class VirtualDisplayAvailabilityTest {

    private val trusted = "android.permission.ADD_TRUSTED_DISPLAY"

    @Test
    fun `old android is unavailable`() {
        assertFalse(VirtualDisplayHost.decideAvailability(29, setOf(trusted)).available)
    }

    @Test
    fun `missing trusted permission is unavailable`() {
        assertFalse(VirtualDisplayHost.decideAvailability(31, emptySet()).available)
        assertFalse(VirtualDisplayHost.decideAvailability(31, setOf("android.permission.CAPTURE_VIDEO_OUTPUT")).available)
    }

    @Test
    fun `trusted display permission makes it available`() {
        assertTrue(VirtualDisplayHost.decideAvailability(31, setOf(trusted)).available)
    }
}
