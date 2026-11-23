package com.pilot.agent.assistant.routing

import android.content.Context
import com.pilot.agent.Dbg
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * 本地路由存储。**文件是唯一事实源**：设置页与配置导入都走这个 store。
 *
 * 移植自 Minis 的 `AssistantRouteStore`：原子写（临时文件 + 改名 + 备份）、
 * 载入时自愈（文件损坏就恢复默认）、用户改过的路由不被种子覆盖。
 */
class RouteStore internal constructor(
    private val rootDir: File,
) {
    private val file = File(rootDir, "routes.json")
    private val lock = Any()
    private val _routes = MutableStateFlow<List<AssistantRoute>>(emptyList())
    private val _owners = MutableStateFlow<Map<String, FastPathOwner>>(DefaultOwners.all)

    val routes: StateFlow<List<AssistantRoute>> = _routes.asStateFlow()

    /** 能力 → 执行权归属（LEGACY / SHADOW / ROUTE）。 */
    val owners: StateFlow<Map<String, FastPathOwner>> = _owners.asStateFlow()

    fun ownerFor(capability: String): FastPathOwner =
        _owners.value[capability] ?: FastPathOwner.LEGACY

    /**
     * 切换某个能力的执行权归属。
     *
     * 这是 P4 的逐能力接管入口：先 SHADOW 对照，分歧为 0 再 ROUTE。
     */
    fun setOwner(capability: String, owner: FastPathOwner) {
        require(CapabilityRegistry.get(capability) != null) {
            "Unknown capability: $capability"
        }
        synchronized(lock) {
            writeAll(_routes.value, ownerOverrides = _owners.value + (capability to owner))
        }
    }

    init {
        ensureLoaded()
    }

    fun snapshot(): List<AssistantRoute> = _routes.value

    fun find(id: String): AssistantRoute? = _routes.value.firstOrNull { it.id == id }

    fun add(route: AssistantRoute): AssistantRoute {
        validate(route)
        synchronized(lock) {
            if (_routes.value.any { it.id == route.id }) {
                throw IllegalArgumentException("Duplicate route id: ${route.id}")
            }
            writeAll(_routes.value + route)
        }
        return route
    }

    fun update(route: AssistantRoute): AssistantRoute {
        validate(route)
        synchronized(lock) {
            if (_routes.value.none { it.id == route.id }) {
                throw IllegalArgumentException("Unknown route id: ${route.id}")
            }
            writeAll(_routes.value.map { if (it.id == route.id) route else it })
        }
        return route
    }

    fun update(id: String, transform: (AssistantRoute) -> AssistantRoute) {
        val current = find(id) ?: throw IllegalArgumentException("Unknown route id: $id")
        update(transform(current))
    }

    fun remove(id: String) {
        synchronized(lock) {
            val next = _routes.value.filterNot { it.id == id }
            if (next.size == _routes.value.size) throw IllegalArgumentException("Unknown route id: $id")
            writeAll(next)
        }
    }

    fun resetDefaults() {
        synchronized(lock) {
            writeAll(DefaultRoutes.all, seedVersion = DefaultRoutes.SEED_VERSION, force = true)
        }
    }

    fun exportJson(): String = JSONObject().apply {
        put("schemaVersion", SCHEMA_VERSION)
        put("defaultsVersion", DefaultRoutes.SEED_VERSION)
        put("ownersVersion", OWNERS_VERSION)
        put("routes", JSONArray().also { arr -> _routes.value.forEach { arr.put(it.toJson()) } })
        put("owners", ownersJson(_owners.value))
    }.toString(2)

    /**
     * 导入会先校验整份规则集，校验全过才整体替换，
     * 坏文件不能把 store 写成半截。
     */
    fun importJson(json: String): Int {
        val parsed = parseDocument(json)
        parsed.forEach { validate(it) }
        val duplicate = parsed.groupingBy { it.id }.eachCount().filterValues { it > 1 }
        if (duplicate.isNotEmpty()) {
            throw IllegalArgumentException("Duplicate route ids: ${duplicate.keys.joinToString()}")
        }
        if (parsed.size > MAX_ROUTES) {
            throw IllegalArgumentException("At most $MAX_ROUTES routes are allowed")
        }
        val importedOwners = runCatching {
            JSONObject(json.trim()).optJSONObject("owners")
        }.getOrNull()?.let { parseOwners(it) }
        synchronized(lock) {
            writeAll(
                parsed,
                seedVersion = DefaultRoutes.SEED_VERSION,
                force = true,
                ownerOverrides = importedOwners,
            )
        }
        return parsed.size
    }

    fun validate(route: AssistantRoute) {
        require(route.id.matches(Regex("""[A-Za-z0-9_.-]{1,80}"""))) {
            "Route id must be 1-80 chars of letters, digits, '.', '_' or '-'"
        }
        require(route.label.isNotBlank() && route.label.length <= 80) {
            "Route label is required (max 80 chars)"
        }
        require(CapabilityRegistry.supports(route.capability, route.action)) {
            "Unknown capability/action: ${route.capability}/${route.action}"
        }
        require(route.keywords.size <= MAX_KEYWORDS) { "At most $MAX_KEYWORDS keywords per route" }
        require(route.allKeywords.size <= MAX_KEYWORDS) { "At most $MAX_KEYWORDS allKeywords per route" }
        require(route.patterns.size <= MAX_PATTERNS) { "At most $MAX_PATTERNS patterns per route" }
        require(route.exclude.size <= MAX_KEYWORDS) { "At most $MAX_KEYWORDS exclude terms per route" }
        require(route.examples.size <= MAX_KEYWORDS) { "At most $MAX_KEYWORDS examples per route" }
        require(route.keywords.all { it.length <= 80 }) { "A keyword is longer than 80 chars" }
        require(route.allKeywords.all { it.length <= 80 }) { "An allKeywords term is longer than 80 chars" }
        require(route.exclude.all { it.length <= 80 }) { "An exclude term is longer than 80 chars" }
        require(route.patterns.all { it.length <= 200 }) { "A pattern is longer than 200 chars" }
        require(route.examples.all { it.length <= 200 }) { "An example is longer than 200 chars" }
        route.patterns.forEach { pattern ->
            try {
                Regex(pattern)
            } catch (e: Exception) {
                throw IllegalArgumentException("Invalid regular expression: $pattern (${e.message})")
            }
        }
        require(route.priority in -10_000..10_000) { "priority must be between -10000 and 10000" }
    }

    private fun ensureLoaded() {
        synchronized(lock) {
            rootDir.mkdirs()
            if (!file.isFile) {
                writeAll(DefaultRoutes.all, DefaultRoutes.SEED_VERSION, force = true)
                return
            }
            val doc = runCatching { JSONObject(file.readText()) }.getOrNull()
            if (doc == null) {
                Dbg.w(TAG, "routes.json 损坏，恢复默认")
                writeAll(DefaultRoutes.all, DefaultRoutes.SEED_VERSION, force = true)
                return
            }
            val loaded = runCatching { parseRoutesObject(doc) }.getOrElse { e ->
                Dbg.w(TAG, "routes.json 非法（${e.message}），恢复默认")
                writeAll(DefaultRoutes.all, DefaultRoutes.SEED_VERSION, force = true)
                return
            }
            val valid = loaded.filter { route ->
                runCatching { validate(route) }.isSuccess
            }.take(MAX_ROUTES)
            val seedVersion = doc.optInt("defaultsVersion", 0)
            val ownersVersion = doc.optInt("ownersVersion", 0)
            val owners = doc.optJSONObject("owners")?.let { parseOwners(it) } ?: DefaultOwners.all
            val withNewDefaults = DefaultRoutes.migrate(valid, seedVersion).take(MAX_ROUTES)
            _routes.value = withNewDefaults
            _owners.value = normalizeOwners(migrateOwners(owners, ownersVersion))
            if (withNewDefaults.size != loaded.size ||
                seedVersion < DefaultRoutes.SEED_VERSION ||
                ownersVersion < OWNERS_VERSION
            ) {
                writeAll(withNewDefaults, DefaultRoutes.SEED_VERSION, force = true)
            }
        }
    }

    private fun parseDocument(json: String): List<AssistantRoute> {
        val trimmed = json.trim()
        if (trimmed.startsWith("[")) {
            return parseRoutesArray(JSONArray(trimmed))
        }
        return parseRoutesObject(JSONObject(trimmed))
    }

    private fun parseRoutesObject(o: JSONObject): List<AssistantRoute> {
        val arr = o.optJSONArray("routes")
            ?: if (o.has("id")) JSONArray().put(o) else JSONArray()
        return parseRoutesArray(arr)
    }

    private fun parseRoutesArray(arr: JSONArray): List<AssistantRoute> = buildList {
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            add(AssistantRoute.fromJson(o))
        }
    }

    /** owners 只认已知能力；未知键忽略（可能是旧版本/手写错误）。 */
    private fun parseOwners(o: JSONObject): Map<String, FastPathOwner> = buildMap {
        val keys = o.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (CapabilityRegistry.get(key) == null) continue
            put(key, DefaultOwners.parse(o.optString(key)))
        }
    }

    /**
     * 补齐缺失能力：存储里没写的能力用**出厂默认**（[DefaultOwners.all]）。
     *
     * 以前这里一律回 LEGACY：那时出厂默认全是 LEGACY，两者等价。现在出厂默认
     * 里有已验证接管的能力，新增能力（存储里还没有键）就该按出厂默认接管，
     * 而不是白扔一次验证结果。用户显式写过的值一律优先。
     */
    private fun normalizeOwners(owners: Map<String, FastPathOwner>): Map<String, FastPathOwner> =
        DefaultOwners.all.mapValues { (capability, fallback) ->
            owners[capability] ?: fallback
        }

    /**
     * 旧版默认值是 LEGACY：这些设备升级后快路径会整体拒绝执行，而遗留匹配器
     * 已不在主链路，等于能力全部停摆。这里按 ownersVersion 做一次性迁移：
     * 已验证接管（[DefaultOwners.promoted]）的能力，若存储里还是 LEGACY，
     * 提升为 ROUTE；SHADOW/ROUTE 与用户显式值保持不变。
     */
    private fun migrateOwners(
        owners: Map<String, FastPathOwner>,
        fromVersion: Int,
    ): Map<String, FastPathOwner> {
        if (fromVersion >= OWNERS_VERSION) return owners
        return owners.mapValues { (capability, owner) ->
            if (owner == FastPathOwner.LEGACY && capability in DefaultOwners.promoted) {
                FastPathOwner.ROUTE
            } else {
                owner
            }
        }
    }

    private fun ownersJson(owners: Map<String, FastPathOwner>): JSONObject = JSONObject().apply {
        normalizeOwners(owners).forEach { (capability, owner) -> put(capability, owner.name) }
    }

    private fun writeAll(
        routes: List<AssistantRoute>,
        seedVersion: Int = DefaultRoutes.SEED_VERSION,
        force: Boolean = false,
        ownerOverrides: Map<String, FastPathOwner>? = null,
    ) {
        rootDir.mkdirs()
        val owners = normalizeOwners(ownerOverrides ?: _owners.value)
        val doc = JSONObject().apply {
            put("schemaVersion", SCHEMA_VERSION)
            put("defaultsVersion", seedVersion)
            put("ownersVersion", OWNERS_VERSION)
            put("routes", JSONArray().also { arr -> routes.forEach { arr.put(it.toJson()) } })
            put("owners", ownersJson(owners))
        }
        if (!force && routes == _routes.value && owners == _owners.value && file.isFile) return
        val tmp = File(rootDir, "routes.json.tmp")
        tmp.writeText(doc.toString(2))
        if (file.exists()) {
            val backup = File(rootDir, "routes.json.bak")
            runCatching { file.copyTo(backup, overwrite = true) }
        }
        if (!tmp.renameTo(file)) {
            // P1：Windows 上 rename 到已存在文件会失败（JVM 单测也会踩到）。
            // 此时先删除旧文件再 rename——中途失败可从刚才的 .bak 恢复，
            // 绝不用 copyTo(overwrite) 直接截断事实源。
            val backup = File(rootDir, "routes.json.bak")
            val hadBackup = backup.isFile
            runCatching { file.delete() }
            if (!tmp.renameTo(file)) {
                Dbg.w(TAG, "路由持久化失败，尝试从备份恢复")
                if (hadBackup) runCatching { backup.copyTo(file, overwrite = true) }
                return
            }
        }
        _routes.value = routes
        _owners.value = owners
    }

    companion object {
        const val SCHEMA_VERSION = 2
        /** owners 迁移版本：旧版 routes.json 没有该字段，读出 0 时触发一次升级。 */
        const val OWNERS_VERSION = 2
        const val MAX_ROUTES = 200
        const val MAX_KEYWORDS = 20
        const val MAX_PATTERNS = 20
        private const val TAG = "RouteStore"

        @Volatile
        private var instance: RouteStore? = null

        fun get(context: Context): RouteStore {
            return instance ?: synchronized(this) {
                instance ?: RouteStore(File(context.applicationContext.filesDir, "assistant"))
                    .also { instance = it }
            }
        }
    }
}
