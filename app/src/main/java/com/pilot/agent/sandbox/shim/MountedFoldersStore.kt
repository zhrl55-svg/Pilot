package com.pilot.agent.sandbox.shim

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 挂载目录存储（minis 同名类的 Pilot 简化版）。
 *
 * minis 原版 392 行挂在 SAF tree uri + DataStore 上，给「用户挑文件夹挂进容器」
 * 的完整 UI 链路用。Pilot v1 的调用面只有 `entries: StateFlow<List<Entry>>`
 * （PRootKernel / MountedFolderCoordinator 三个方法），这里保留同一形状：
 * - Entry 字段名与原版一致（kernel 代码零改动）
 * - resolvedHostPath 直接走 POSIX 路径（/sdcard 下的目录，proot bind mount 只认路径）
 * - 持久化用 SharedPreferences + JSON（够用且无新依赖）
 *
 * v2 接 SAF 时再换实现，内核不用动。
 */
class MountedFoldersStore(context: Context) {

    data class Entry(
        val id: String = UUID.randomUUID().toString(),
        var name: String,
        val sourceDisplayName: String,
        val treeUri: String,
        val createdAt: Long = System.currentTimeMillis(),
        var isWritable: Boolean = true,
        var userAllowWrite: Boolean = true,
        var resolvedHostPath: String? = null,
    ) {
        val effectiveWritable: Boolean get() = isWritable && userAllowWrite
    }

    private val prefs = context.applicationContext
        .getSharedPreferences("pilot_mounted_folders", Context.MODE_PRIVATE)

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    init {
        runCatching { load() }
    }

    private fun load() {
        val raw = prefs.getString("entries", null) ?: return
        val arr = JSONArray(raw)
        val list = ArrayList<Entry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            list += Entry(
                id = o.optString("id", UUID.randomUUID().toString()),
                name = o.optString("name"),
                sourceDisplayName = o.optString("sourceDisplayName"),
                treeUri = o.optString("treeUri"),
                createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                isWritable = o.optBoolean("isWritable", true),
                userAllowWrite = o.optBoolean("userAllowWrite", true),
                resolvedHostPath = o.optString("resolvedHostPath").takeIf { it.isNotEmpty() }
                    ?: resolveHostPathFromUri(o.optString("treeUri")),
            )
        }
        _entries.value = list
    }

    private fun persist(list: List<Entry>) {
        val arr = JSONArray()
        for (e in list) {
            arr.put(JSONObject().apply {
                put("id", e.id); put("name", e.name)
                put("sourceDisplayName", e.sourceDisplayName)
                put("treeUri", e.treeUri); put("createdAt", e.createdAt)
                put("isWritable", e.isWritable); put("userAllowWrite", e.userAllowWrite)
                put("resolvedHostPath", e.resolvedHostPath ?: "")
            })
        }
        prefs.edit().putString("entries", arr.toString()).apply()
    }

    /** 挂一个目录（name 决定 guest 里的 /var/minis/mounts/<name>）。 */
    fun add(name: String, hostPath: String, writable: Boolean = true): Entry {
        val entry = Entry(
            name = name,
            sourceDisplayName = name,
            treeUri = Uri.fromFile(java.io.File(hostPath)).toString(),
            isWritable = writable,
            resolvedHostPath = hostPath,
        )
        val list = _entries.value.filter { it.name != name } + entry
        _entries.value = list
        persist(list)
        return entry
    }

    fun remove(id: String): Boolean {
        val list = _entries.value
        val hit = list.firstOrNull { it.id == id } ?: return false
        _entries.value = list - hit
        persist(_entries.value)
        return true
    }

    fun removeByName(name: String): Boolean {
        val list = _entries.value
        val hit = list.firstOrNull { it.name == name } ?: return false
        return remove(hit.id)
    }

    /** SAF tree uri → POSIX 路径（只支持 /storage/... 型 external storage，与 minis 同规则）。 */
    private fun resolveHostPathFromUri(treeUri: String): String? {
        if (!treeUri.startsWith("content://") && treeUri.startsWith("file://")) {
            return treeUri.removePrefix("file://")
        }
        val parsed = runCatching { Uri.parse(treeUri).path }.getOrNull() ?: return null
        val marker = "/document/"
        val idx = parsed.indexOf(marker)
        if (idx < 0) return null
        val docId = Uri.decode(parsed.substring(idx + marker.length))
        // primary/External → /storage/emulated/0
        if (docId.startsWith("primary")) {
            val sub = docId.removePrefix("primary").removePrefix(":")
            return ("/storage/emulated/0" + if (sub.isEmpty()) "" else "/$sub")
        }
        return docId.takeIf { it.startsWith("/") }
    }
}
