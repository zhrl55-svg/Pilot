package com.pilot.agent.assistant.routing

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 路由存储的行为契约（移植自 Minis 的 `AssistantRouteStoreTest`）。
 */
class RouteStoreTest {

    private fun tempDir() = Files.createTempDirectory("pilot-routes").toFile()

    @Test
    fun `a fresh store is seeded with the default routes`() {
        val store = RouteStore(tempDir())
        assertTrue(store.snapshot().isNotEmpty())
        val ids = store.snapshot().map { it.id }
        assertTrue(ids.contains("life.app-open"))
        assertTrue(ids.contains("life.volume-set"))
        assertTrue(ids.contains("tool.file-write"))
    }

    @Test
    fun `unknown capability is rejected`() {
        val store = RouteStore(tempDir())
        val bad = AssistantRoute(
            id = "bad",
            label = "bad",
            capability = "not_a_capability",
            action = "nope",
        )
        val error = runCatching { store.validate(bad) }.exceptionOrNull()
        assertTrue("expected a validation failure", error is IllegalArgumentException)
    }

    @Test
    fun `updates survive a reload`() {
        val dir = tempDir()
        val store = RouteStore(dir)
        val original = store.find("life.volume-set")!!
        store.update(original.id) { it.copy(priority = 7) }

        val reloaded = RouteStore(dir)
        assertEquals(7, reloaded.find("life.volume-set")!!.priority)
    }

    @Test
    fun `a malformed file falls back to the defaults`() {
        val dir = tempDir()
        val store = RouteStore(dir)
        java.io.File(dir, "routes.json").writeText("{ not json")
        val reloaded = RouteStore(dir)
        assertEquals(store.snapshot().size, reloaded.snapshot().size)
    }

    @Test
    fun `export and import round trip`() {
        val store = RouteStore(tempDir())
        val json = store.exportJson()
        val dir = tempDir()
        val target = RouteStore(dir)
        val count = target.importJson(json)
        assertEquals(store.snapshot().size, count)
    }
}
