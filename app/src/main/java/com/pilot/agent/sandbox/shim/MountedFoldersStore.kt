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
class MountedFoldersStore(private val appContext: Context) {

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

    private val prefs = appContext.applicationContext
        .getSharedPreferences("pilot_mounted_folders", Context.MODE_PRIVATE)

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    init {
        runCatching { load() }
    }

    private fun load() {
        // 优先读共享文件（跨进程可见），没有再退回 SP（旧数据迁移）
        val f = mountsFile()
        if (f.isFile) {
            runCatching { loadFromJson(f.readText()) }
            return
        }
        val raw = prefs.getString("entries", null) ?: return
        runCatching { loadFromJson(raw) }
    }

    private fun loadFromJson(raw: String) {
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

    /**
     * 从磁盘重新加载（跨进程场景必需）。
     *
     * 挂载表要跨进程共享：主进程（UI）写，`:sandbox` 进程读。
     *
     * ## 为什么不用 SharedPreferences
     * SP 的跨进程缓存失效依赖 MODE_MULTI_PROCESS，而那个 flag 从 API 23 起
     * 就被废弃了——AOSP 里 `getSharedPreferences` 会直接忽略它，
     * 结果就是 :sandbox 进程永远读到启动时那份内存副本，
     * 主进程新加的挂载它永远不知道（踩过）。
     *
     * ## 方案：文件 + mtime 判重
     * 挂载表落到沙箱专属目录下的一个 JSON 文件，两个进程读同一个文件。
     * 用 mtime+size 判「有没有变」，变了才重新解析——
     * 解析很便宜，但每条命令都解析也没必要。
     */
    fun reloadFromDisk() {
        runCatching {
            val f = mountsFile()
            val stamp = if (f.isFile) "${f.lastModified()}:${f.length()}" else "none"
            if (stamp == lastDiskStamp) return@runCatching
            lastDiskStamp = stamp
            if (!f.isFile) {
                if (_entries.value.isNotEmpty()) _entries.value = emptyList()
                return@runCatching
            }
            val arr = JSONArray(f.readText())
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
            if (list != _entries.value) {
                _entries.value = list
            }
        }.onFailure { /* 读不到就沿用内存里的 */ }
    }

    /** 挂载表文件（两进程共享，沙箱专属目录）。 */
    private fun mountsFile(): java.io.File =
        java.io.File(appContext.filesDir, "sandbox/mounts.json")

    @Volatile private var lastDiskStamp: String = ""

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
        // 写共享文件（:sandbox 进程读它）+ 写 SP（备份/旧版本兼容）
        runCatching {
            val f = mountsFile()
            f.parentFile?.mkdirs()
            // 原子写：先写临时文件再 rename，避免另一个进程读到半截 JSON
            val tmp = java.io.File(f.parentFile, f.name + ".tmp")
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(f)) {
                f.writeText(arr.toString())
                tmp.delete()
            }
        }
        prefs.edit().putString("entries", arr.toString()).apply()
        // 本进程的下次 reload 会因为 mtime 变了而重新解析，刷新指纹缓存
        lastDiskStamp = ""
    }

    /**
     * 挂载集合变化时回调（增/删/改都触发）。
     *
     * ★ 热挂载的关键（minis 原版 T219-5 同款）：
     *   proot 的 `-b` 参数在**进程 spawn 时**就冻结了，
     *   所以光改 bindMounts 快照对已经跑着的 shell 没用——
     *   必须杀掉热壳，下一条命令重建时带上新的 bind 集合。
     *   回调里做两件事：applyMountedFoldersSnapshot + stopCurrentCommand()。
     */
    var onChange: (() -> Unit)? = null

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
        notifyChanged()
        return entry
    }

    /**
     * SAF 目录挂载（v2 引导式 UI 用）：tree uri 持久化授权 + 解析 POSIX 路径。
     * proot bind mount 只认宿主 POSIX 路径，所以非 externalstorage 的 uri
     * （解析不出 /storage/... ）直接拒绝并说明原因，别让 guest 挂半截。
     */
    fun addFromTreeUri(displayName: String, uri: Uri): Result<Boolean> {
        val hostPath = resolveHostPathFromUri(uri.toString())
            ?: return Result.failure(
                IllegalStateException("这个目录不在共享存储上（$uri），proot 挂不了，请选「手机存储」下的文件夹"),
            )
        runCatching {
            appContext.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } // 授权失败不致命：POSIX 路径本来就直接可读
        val name = displayName.ifBlank { hostPath.trimEnd('/').substringAfterLast('/') }
            .sanitizeMountName()
        add(name, hostPath)
        return Result.success(true)
    }

    private fun String.sanitizeMountName(): String =
        replace(Regex("[^\\w.\\-\\u4e00-\\u9fa5]"), "_").take(24).ifBlank { "mnt" }

    fun remove(id: String): Boolean {
        val list = _entries.value
        val hit = list.firstOrNull { it.id == id } ?: return false
        _entries.value = list - hit
        persist(_entries.value)
        notifyChanged()
        return true
    }

    fun removeByName(name: String): Boolean {
        val list = _entries.value
        val hit = list.firstOrNull { it.name == name } ?: return false
        return remove(hit.id)
    }

    /** 通知外部：挂载集合变了，需要重建 proot 的 bind 参数。 */
    private fun notifyChanged() {
        runCatching { onChange?.invoke() }
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
