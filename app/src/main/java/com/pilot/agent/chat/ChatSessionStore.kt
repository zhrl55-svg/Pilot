package com.pilot.agent.chat

import com.pilot.agent.Dbg
import com.pilot.agent.query.SecretVault
import org.json.JSONArray
import org.json.JSONObject

/**
 * 首页「最近会话」的本地存储。
 *
 * 以前对话只活在内存里，退出 App 就没了；这里把最近若干个会话
 * 连同上下文问答一起落盘，重启后可以接着聊。
 *
 * 聊天内容可能包含隐私信息，所以和待办一样走 [SecretVault]
 * （AndroidKeyStore AES-GCM），不写明文 SharedPreferences。
 */
object ChatSessionStore {
    private const val TAG = "ChatSessions"
    private const val KEY = "chat_sessions_v1"
    private const val ACTIVE_KEY = "chat_active_session_v1"

    /** 最多保留的会话数；再多就淘汰最旧的。 */
    private const val MAX_SESSIONS = 20

    /** 单会话最多回放的聊天条数（内存里的日志上限是 200）。 */
    private const val MAX_MESSAGES = 100

    /** 单会话最多保留的上下文问答轮数（与 VM 的 chatHistory 上限一致）。 */
    private const val MAX_HISTORY = 40

    data class Message(val kind: String, val text: String)

    data class Session(
        val id: String,
        val title: String,
        val createdAt: Long,
        val updatedAt: Long,
        val messages: List<Message>,
        val history: List<Pair<String, String>>,
    )

    @Synchronized
    fun loadAll(): List<Session> {
        val raw = runCatching { SecretVault.get(KEY) }.getOrDefault("")
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    val id = o.optString("id").trim()
                    if (id.isEmpty()) continue
                    val messagesJson = o.optJSONArray("messages") ?: JSONArray()
                    val messages = buildList {
                        for (j in 0 until messagesJson.length()) {
                            val m = messagesJson.optJSONObject(j) ?: continue
                            val text = m.optString("text")
                            if (text.isBlank()) continue
                            add(Message(m.optString("kind"), text))
                        }
                    }
                    if (messages.isEmpty()) continue
                    val historyJson = o.optJSONArray("history") ?: JSONArray()
                    val history = buildList {
                        for (j in 0 until historyJson.length()) {
                            val h = historyJson.optJSONObject(j) ?: continue
                            val q = h.optString("q")
                            if (q.isBlank()) continue
                            add(q to h.optString("a"))
                        }
                    }
                    add(
                        Session(
                            id = id,
                            title = o.optString("title").ifBlank { "新会话" },
                            createdAt = o.optLong("createdAt"),
                            updatedAt = o.optLong("updatedAt"),
                            messages = messages,
                            history = history,
                        ),
                    )
                }
            }.sortedByDescending { it.updatedAt }
        }.getOrElse {
            Dbg.w(TAG, "读取会话失败：${it.message}")
            emptyList()
        }
    }

    @Synchronized
    fun saveAll(sessions: List<Session>) {
        runCatching {
            val array = JSONArray()
            sessions.sortedByDescending { it.updatedAt }.take(MAX_SESSIONS).forEach { s ->
                array.put(
                    JSONObject().apply {
                        put("id", s.id)
                        put("title", s.title.take(80))
                        put("createdAt", s.createdAt)
                        put("updatedAt", s.updatedAt)
                        put("messages", JSONArray().apply {
                            s.messages.takeLast(MAX_MESSAGES).forEach { m ->
                                put(
                                    JSONObject()
                                        .put("kind", m.kind)
                                        .put("text", m.text.take(4_000)),
                                )
                            }
                        })
                        put("history", JSONArray().apply {
                            s.history.takeLast(MAX_HISTORY).forEach { (q, a) ->
                                put(JSONObject().put("q", q.take(1_000)).put("a", a.take(2_000)))
                            }
                        })
                    },
                )
            }
            SecretVault.put(KEY, array.toString())
        }.onFailure { Dbg.w(TAG, "保存会话失败：${it.message}") }
    }

    fun activeSessionId(): String? =
        runCatching { SecretVault.get(ACTIVE_KEY) }.getOrDefault("").ifBlank { null }

    fun saveActiveSessionId(id: String?) {
        runCatching { SecretVault.put(ACTIVE_KEY, id.orEmpty()) }
            .onFailure { Dbg.w(TAG, "保存当前会话失败：${it.message}") }
    }
}
