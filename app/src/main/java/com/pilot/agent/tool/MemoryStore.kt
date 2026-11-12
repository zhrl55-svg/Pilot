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
        // Version 1 is the first release. Future migrations go here.
    }

    fun remember(
        rawContent: String,
        rawTags: String = "",
        importance: Double = 0.65,
    ): Memory? {
        val content = rawContent.trim().trim('，', ',', '。', '.', '；', ';')
        if (content.length < 2) return null
        if (content.length > MAX_CONTENT_CHARS) return null
        val existing = all().firstOrNull {
            normalize(it.content) == normalize(content)
        }
        if (existing != null) return existing
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
            .map { memory ->
                val content = normalize(memory.content)
                val tags = normalize(memory.tags)
                var score = 0.0
                if (content.contains(normalizedQuery) || tags.contains(normalizedQuery)) {
                    score += 8.0
                }
                queryGrams.forEach { gram ->
                    if (content.contains(gram)) score += 1.0
                    if (tags.contains(gram)) score += 0.8
                }
                val ageDays = ((System.currentTimeMillis() - memory.updatedAt).coerceAtLeast(0L) /
                    86_400_000.0)
                score += memory.importance
                score += (2.0 / (1.0 + ageDays / 30.0))
                memory to score
            }
            .filter { it.second >= 1.2 }
            .sortedByDescending { it.second }
            .take(limit.coerceIn(1, 20))
            .map { it.first }
            .toList()
    }

    fun forget(query: String): List<Memory> {
        val clean = query.trim().trim('：', ':', '，', ',', '。', '.', ' ')
        if (clean.isBlank()) return emptyList()
        val candidates = search(clean, 20).ifEmpty {
            all().filter {
                normalize(it.content).contains(normalize(clean)) ||
                    normalize(it.tags).contains(normalize(clean))
            }.take(20)
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

    fun promptContext(query: String, limit: Int = 5): String {
        val items = search(query, limit)
        if (items.isEmpty()) return ""
        return items.joinToString("\n") { "• ${it.content}" }
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
        if (value.length < 2) return setOf(value)
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
