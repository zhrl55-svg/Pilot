package com.pilot.agent.music

import com.pilot.agent.PilotApp
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pilot 自己的轻量最近播放缓存。
 *
 * 网易云 `/user/record` 只包含已经在网易账号留下播放记录的歌；Pilot 直接拉流时
 * 服务端可能还没来得及记录。因此本地再保留最近 30 首，网络结果为空时仍能执行
 * “播放最近”。
 */
object MusicHistoryStore {
    private const val PREFS = "pilot"
    private const val KEY = "music_recent_json"
    private const val LIMIT = 30

    @Synchronized
    fun remember(songs: List<NeteaseApi.Song>) {
        if (songs.isEmpty()) return
        val merged = LinkedHashMap<Long, NeteaseApi.Song>()
        songs.forEach { merged[it.id] = it }
        recent(LIMIT).forEach { merged.putIfAbsent(it.id, it) }
        val array = JSONArray()
        merged.values.take(LIMIT).forEach { song ->
            array.put(
                JSONObject()
                    .put("id", song.id)
                    .put("name", song.name)
                    .put("artist", song.artist)
                    .put("duration", song.durationMs),
            )
        }
        PilotApp.instance.getSharedPreferences(PREFS, 0)
            .edit()
            .putString(KEY, array.toString())
            .apply()
    }

    @Synchronized
    fun recent(limit: Int = LIMIT): List<NeteaseApi.Song> {
        val raw = PilotApp.instance.getSharedPreferences(PREFS, 0)
            .getString(KEY, null)
            .orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until minOf(array.length(), limit)) {
                    val item = array.optJSONObject(i) ?: continue
                    val id = item.optLong("id")
                    val name = item.optString("name").trim()
                    if (id > 0L && name.isNotBlank()) {
                        add(
                            NeteaseApi.Song(
                                id = id,
                                name = name,
                                artist = item.optString("artist"),
                                durationMs = item.optLong("duration"),
                            ),
                        )
                    }
                }
            }
        }.getOrDefault(emptyList())
    }
}
