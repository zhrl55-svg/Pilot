package com.pilot.agent.tool

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.pilot.agent.Dbg
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Explicit, on-device long-term memory.
 *
 * Only phrases such as "记住…" create records. Conversations are not uploaded
 * or mirrored automatically. Search is done in Kotlin because Chinese FTS
 * tokenization is inconsistent across Android SQLite builds.
 */
class MemoryStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE,
    null,
    VERSION,
) {
    data class Memory(
        val id: Long,
        val content: String,
        val tags: String,
        val source: String,
        val importance: Double,
        val createdAt: Long,
        val updatedAt: Long,
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                content TEXT NOT NULL,
                tags TEXT NOT NULL DEFAULT '',
                source TEXT NOT NULL DEFAULT 'explicit',
                importance REAL NOT NULL DEFAULT 0.5,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_memory_updated ON $TABLE(updated_at DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 是首个版本，当前没有迁移。
        // 注意：提升 VERSION 时必须逐版本补齐列，否则老库缺列会让
        // toMemory() 的 getColumnIndexOrThrow 直接崩溃。
    }

    fun remember(
        rawContent: String,
        rawTags: String = "",
        importance: Double = 0.65,
    ): Memory? {
        val content = rawContent.trim().trim('，', ',', '。', '.', '；', ';')
        if (content.length < 2) return null
        if (content.length > MAX_CONTENT_CHARS) return null
        // P1：查重与插入在同一事务内串行化，避免快路径与 Agent 链路并发写入重复记忆。
        return synchronized(this) {
            val db = writableDatabase
            db.beginTransaction()
            try {
                val existing = findByExactContent(content) ?: all().firstOrNull {
                    normalize(it.content) == normalize(content)
                }
                val result = if (existing != null) existing
                else insertMemory(content, rawTags, importance)
                db.setTransactionSuccessful()
                result
            } finally {
                db.endTransaction()
            }
        }
    }

    private fun insertMemory(
        content: String,
        rawTags: String,
        importance: Double,
    ): Memory? {
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("content", content)
            put("tags", rawTags.trim().take(160))
            put("source", "explicit")
            put("importance", importance.coerceIn(0.0, 1.0))
            put("created_at", now)
            put("updated_at", now)
        }
        val id = writableDatabase.insert(TABLE, null, values)
        if (id < 0) {
            Dbg.w(TAG, "insert failed")
            return null
        }
        return Memory(id, content, rawTags, "explicit", importance, now, now)
    }

    /** SQL 精确命中优先，避免每次「记住…」都全表扫描 + 字符串规范化。 */
    private fun findByExactContent(content: String): Memory? =
        readableDatabase.query(
            TABLE, COLUMNS, "content = ?", arrayOf(content), null, null, null, "1",
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toMemory() else null }

    fun all(limit: Int = MAX_RECORDS): List<Memory> {
        val rows = ArrayList<Memory>()
        readableDatabase.query(
            TABLE,
            COLUMNS,
            null,
            null,
            null,
            null,
            "updated_at DESC",
            limit.coerceIn(1, MAX_RECORDS).toString(),
        ).use { cursor ->
            while (cursor.moveToNext()) rows += cursor.toMemory()
        }
        return rows
    }

    fun search(query: String, limit: Int = 5): List<Memory> {
        val normalizedQuery = normalize(query
            .replace("你还记得", "")
            .replace("还记得", "")
            .replace("你记得", "")
            .replace("吗", "")
            .replace("什么", "")
        )
        if (normalizedQuery.isBlank()) return emptyList()
        val queryGrams = grams(normalizedQuery)
        return all().asSequence()
            .mapNotNull { memory ->
                val content = normalize(memory.content)
                val tags = normalize(memory.tags)
                // 相关性只由「命中」产生。旧实现把 importance/recency 直接加进 score，
                // 而这两项基数（0.65 + 约 2.0）本来就超过阈值 1.2，导致**任何近期记忆
                // 都会被判为相关**并塞进 prompt，也让 forget 误删无关记录。
                var relevance = 0.0
                if (content.contains(normalizedQuery) || tags.contains(normalizedQuery)) {
                    relevance += 8.0
                }
                queryGrams.forEach { gram ->
                    if (content.contains(gram)) relevance += 1.0
                    if (tags.contains(gram)) relevance += 0.8
                }
                if (relevance <= 0.0) return@mapNotNull null
                val ageDays = ((System.currentTimeMillis() - memory.updatedAt).coerceAtLeast(0L) /
                    86_400_000.0)
                // importance / 新鲜度只作为「同等相关」时的排序加成，不能把无关项拉过门槛。
                val rank = relevance +
                    memory.importance.coerceIn(0.0, 1.0) * 0.5 +
                    (1.0 / (1.0 + ageDays / 30.0)) * 0.3
                memory to rank
            }
            .sortedByDescending { it.second }
            .take(limit.coerceIn(1, 20))
            .map { it.first }
            .toList()
    }

    fun forget(query: String): List<Memory> {
        val clean = query.trim().trim('：', ':', '，', ',', '。', '.', ' ')
        if (clean.isBlank()) return emptyList()
        // 只删除确实与关键词相关的记录，且限制条数；旧实现依赖宽泛的 search，
        // 一次「忘掉XX」可能连带清掉多条无关记忆。
        val candidateLimit = 5
        val candidates = search(clean, candidateLimit).ifEmpty {
            all().filter {
                normalize(it.content).contains(normalize(clean)) ||
                    normalize(it.tags).contains(normalize(clean))
            }.take(candidateLimit)
        }
        val ids = candidates.joinToString(",") { it.id.toString() }
        if (ids.isNotBlank()) writableDatabase.delete(TABLE, "id IN ($ids)", null)
        return candidates
    }

    fun deleteById(id: Long): Boolean =
        writableDatabase.delete(TABLE, "id = ?", arrayOf(id.toString())) > 0

    fun count(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE", null).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    /**
     * 注入 prompt 的记忆摘要。
     *
     * @param maxChars 总长度预算。记忆单条上限 1000 字，5 条最多能到 5000 字，
     *   直接塞进 system prompt 会挤占对话与屏幕信息，所以这里按预算截断。
     */
    fun promptContext(
        query: String,
        limit: Int = 5,
        maxChars: Int = 1_200,
    ): String {
        val items = search(query, limit)
        if (items.isEmpty()) return ""
        val out = StringBuilder()
        for (memory in items) {
            val line = "• ${memory.content.take(300)}"
            if (out.isNotEmpty() && out.length + 1 + line.length > maxChars) break
            if (out.isNotEmpty()) out.append('\n')
            out.append(line)
        }
        return out.toString()
    }

    private fun android.database.Cursor.toMemory() = Memory(
        id = getLong(getColumnIndexOrThrow("id")),
        content = getString(getColumnIndexOrThrow("content")),
        tags = getString(getColumnIndexOrThrow("tags")),
        source = getString(getColumnIndexOrThrow("source")),
        importance = getDouble(getColumnIndexOrThrow("importance")),
        createdAt = getLong(getColumnIndexOrThrow("created_at")),
        updatedAt = getLong(getColumnIndexOrThrow("updated_at")),
    )

    private fun normalize(value: String): String =
        value.lowercase(Locale.ROOT)
            .replace(Regex("[\\p{P}\\p{S}\\s]+"), "")

    private fun grams(value: String): Set<String> {
        // 单字查询不做 bigram：否则「查」这类单字会命中几乎全部记忆。
        // 单字仍可通过整串 contains 精确命中。
        if (value.length < 2) return emptySet()
        return value.windowed(2).toSet()
    }

    fun formatList(items: List<Memory>, empty: String = "还没有保存长期记忆。"): String {
        if (items.isEmpty()) return empty
        val date = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
        return buildString {
            append("长期记忆（${items.size} 条）：")
            items.take(20).forEach { memory ->
                append("\n• ").append(memory.content)
                append("（").append(date.format(Date(memory.updatedAt))).append("）")
            }
        }
    }

    companion object {
        private const val TAG = "PilotMemory"
        private const val DATABASE = "pilot_memory.db"
        private const val TABLE = "memories"
        private const val VERSION = 1
        private const val MAX_RECORDS = 1000
        private const val MAX_CONTENT_CHARS = 1000
        private val COLUMNS = arrayOf(
            "id", "content", "tags", "source", "importance", "created_at", "updated_at",
        )
    }
}
