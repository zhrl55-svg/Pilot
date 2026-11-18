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

    @Test
    fun `owners follow the shipped defaults`() {
        // 出厂默认 = 已过真机影子对照的能力接管（见 DefaultOwners）。
        val store = RouteStore(tempDir())
        CapabilityRegistry.all.forEach { capability ->
            assertEquals(
                "「${capability.id}」的默认归属",
                DefaultOwners.all[capability.id],
                store.ownerFor(capability.id),
            )
        }
        // 明确留着的四个：遗留匹配器不接、由下游规划器处理，提升等于换执行路径。
        listOf("app_launch", "volume", "media", "reminder").forEach {
            assertEquals(FastPathOwner.LEGACY, store.ownerFor(it))
        }
        listOf("device", "memory_get", "memory_write", "screenshot", "file_read", "file_write", "scheduled_task")
            .forEach {
                assertEquals(FastPathOwner.ROUTE, store.ownerFor(it))
            }
    }

    @Test
    fun `owner changes survive a reload`() {
        val dir = tempDir()
        val store = RouteStore(dir)
        store.setOwner("device", FastPathOwner.SHADOW)
        store.setOwner("memory_get", FastPathOwner.ROUTE)

        val reloaded = RouteStore(dir)
        assertEquals(FastPathOwner.SHADOW, reloaded.ownerFor("device"))
        assertEquals(FastPathOwner.ROUTE, reloaded.ownerFor("memory_get"))
        assertEquals(FastPathOwner.LEGACY, reloaded.ownerFor("volume"))
    }

    @Test
    fun `an unknown capability cannot own a route`() {
        val store = RouteStore(tempDir())
        val error = runCatching {
            store.setOwner("not_a_capability", FastPathOwner.ROUTE)
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun `owner state is part of export and import`() {
        val store = RouteStore(tempDir())
        store.setOwner("volume", FastPathOwner.ROUTE)
        val json = store.exportJson()
        assertTrue(json.contains("\"volume\""))
        assertTrue(json.contains("ROUTE"))

        val target = RouteStore(tempDir())
        target.importJson(json)
        assertEquals(FastPathOwner.ROUTE, target.ownerFor("volume"))
    }
}
