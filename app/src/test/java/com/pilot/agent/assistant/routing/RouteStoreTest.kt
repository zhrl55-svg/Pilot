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
            // v1 的 11 个能力全部已提升（2026-11-18 真机影子对照后）。
            assertEquals(
                "「${capability.id}」应当由快路径接管",
                FastPathOwner.ROUTE,
                store.ownerFor(capability.id),
            )
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
        // 没显式改过的能力跟出厂默认走（v1 全部已提升）。
        assertEquals(FastPathOwner.ROUTE, reloaded.ownerFor("file_write"))
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

    @Test
    fun `legacy owners are migrated to route once`() {
        val dir = tempDir()
        RouteStore(dir)
        val file = java.io.File(dir, "routes.json")
        val doc = org.json.JSONObject(file.readText())
        doc.remove("ownersVersion")
        val legacy = org.json.JSONObject()
        CapabilityRegistry.all.forEach { legacy.put(it.id, "LEGACY") }
        doc.put("owners", legacy)
        file.writeText(doc.toString(2))

        val reloaded = RouteStore(dir)
        CapabilityRegistry.all.forEach { capability ->
            assertEquals(
                "旧版 LEGACY 归属应迁移为 ROUTE：${capability.id}",
                FastPathOwner.ROUTE,
                reloaded.ownerFor(capability.id),
            )
        }
        assertTrue(
            "迁移后应写回版本号",
            file.readText().contains("\"ownersVersion\": ${RouteStore.OWNERS_VERSION}"),
        )
    }

    @Test
    fun `explicit shadow survives owners migration`() {
        val dir = tempDir()
        RouteStore(dir).setOwner("device", FastPathOwner.SHADOW)
        val file = java.io.File(dir, "routes.json")
        val doc = org.json.JSONObject(file.readText())
        doc.remove("ownersVersion")
        file.writeText(doc.toString(2))

        val reloaded = RouteStore(dir)
        assertEquals(FastPathOwner.SHADOW, reloaded.ownerFor("device"))
        assertEquals(FastPathOwner.ROUTE, reloaded.ownerFor("file_write"))
    }
}
