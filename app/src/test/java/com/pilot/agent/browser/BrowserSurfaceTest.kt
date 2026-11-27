package com.pilot.agent.browser

import com.pilot.agent.agent.AgentSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 网页打开方式选择：默认小窗；无悬浮窗权限退回后台；用户要“看”则全屏。 */
class BrowserSurfaceTest {

    @Test
    fun `show to user forces fullscreen`() {
        assertEquals(
            BrowserSurface.FULLSCREEN,
            BrowserSessionManager.decideSurface(true, AgentSettings.BROWSER_MODE_OVERLAY, canOverlay = true).surface,
        )
    }

    @Test
    fun `background setting stays headless`() {
        assertEquals(
            BrowserSurface.BACKGROUND,
            BrowserSessionManager.decideSurface(false, AgentSettings.BROWSER_MODE_BACKGROUND, canOverlay = true).surface,
        )
    }

    @Test
    fun `overlay used when permission present`() {
        assertEquals(
            BrowserSurface.OVERLAY,
            BrowserSessionManager.decideSurface(false, AgentSettings.BROWSER_MODE_OVERLAY, canOverlay = true).surface,
        )
    }

    @Test
    fun `overlay without permission falls back to background with note`() {
        val choice = BrowserSessionManager.decideSurface(false, AgentSettings.BROWSER_MODE_OVERLAY, canOverlay = false)
        assertEquals(BrowserSurface.BACKGROUND, choice.surface)
        assertTrue(choice.note.isNotBlank())
    }

    @Test
    fun `fullscreen setting honored`() {
        assertEquals(
            BrowserSurface.FULLSCREEN,
            BrowserSessionManager.decideSurface(false, AgentSettings.BROWSER_MODE_FULLSCREEN, canOverlay = false).surface,
        )
    }
}
