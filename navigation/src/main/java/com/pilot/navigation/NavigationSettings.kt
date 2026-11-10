package com.pilot.navigation

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 导航设置（跨进程持久化）。
 *
 * ## 为什么用文件不用 SharedPreferences
 * 写设置的是主进程（设置界面），读设置的是 `:navigation` 进程（导航服务）。
 * SP 的跨进程缓存靠 `MODE_MULTI_PROCESS`，那个 flag 从 API 23 起就被废弃了，
 * 副进程会一直读到启动时的旧副本（沙箱的挂载表踩过同一个坑）。
 * 所以用**文件 + mtime 判重**。
 *
 * ## 各项说明
 * - `broadcastLevel`：播报详细程度（详细/标准/简洁）
 * - `arriveAutoExit`：到达目的地后自动退出导航
 * - `favorites`：常去地点（家/公司/自定义）
 * - `history`：导航历史（可删除、可改别名）
 * - `keepScreenOn`：导航时屏幕常亮
 * - `avoidJam`：躲避拥堵
 */
class NavigationSettings(private val context: Context) {

    companion object {
        private const val TAG = "PilotNavSettings"
        private const val FILE = "nav_settings.json"

        // ---- 播报详细度 ----
        /** 详细：所有播报都念（含路况、电子眼、红绿灯） */
        const val LEVEL_DETAILED = 0
        /** 标准：过滤掉高频噪音（红绿灯、注意行人），保留转向和路况 */
        const val LEVEL_NORMAL = 1
        /** 简洁：只念转向、到达、偏航重算 */
        const val LEVEL_BRIEF = 2

        @Volatile private var cached: NavSettings? = null
        @Volatile private var cachedStamp: String = ""

        /** 读设置（带 mtime 判重，另一个进程改了能立刻看到）。 */
        fun load(context: Context): NavSettings {
            val f = File(context.filesDir, FILE)
            val stamp = if (f.isFile) "${f.lastModified()}:${f.length()}" else "none"
            cached?.let { c -> if (stamp == cachedStamp) return c }
            val s = runCatching {
                if (!f.isFile) NavSettings() else parse(f.readText())
            }.getOrElse {
                NLog.w(TAG, "读设置失败，用默认值：${it.message}")
                NavSettings()
            }
            cached = s
            cachedStamp = stamp
            return s
        }

        /** 写设置（原子写）。 */
        fun save(context: Context, s: NavSettings) {
            runCatching {
                val f = File(context.filesDir, FILE)
                val tmp = File(f.parentFile, "$FILE.tmp")
                tmp.writeText(serialize(s))
                if (!tmp.renameTo(f)) {
                    f.writeText(serialize(s))
                    tmp.delete()
                }
                cached = s
                cachedStamp = "${f.lastModified()}:${f.length()}"
                NLog.i(TAG, "设置已保存 broadcastLevel=${s.broadcastLevel}")
            }.onFailure { NLog.e(TAG, "保存设置失败", it) }
        }

        private fun parse(text: String): NavSettings {
            val o = JSONObject(text)
            val favs = ArrayList<Favorite>()
            o.optJSONArray("favorites")?.let { a ->
                for (i in 0 until a.length()) {
                    val f = a.optJSONObject(i) ?: continue
                    favs.add(
                        Favorite(
                            label = f.optString("label"),
                            address = f.optString("address"),
                            lat = f.optDouble("lat"),
                            lon = f.optDouble("lon"),
                            poiId = f.optString("poiId").takeIf { it.isNotBlank() },
                        ),
                    )
                }
            }
            val hist = ArrayList<HistoryEntry>()
            o.optJSONArray("history")?.let { a ->
                for (i in 0 until a.length()) {
                    val h = a.optJSONObject(i) ?: continue
                    hist.add(
                        HistoryEntry(
                            name = h.optString("name"),
                            alias = h.optString("alias"),
                            lat = h.optDouble("lat"),
                            lon = h.optDouble("lon"),
                            poiId = h.optString("poiId").takeIf { it.isNotBlank() },
                            time = h.optLong("time"),
                        ),
                    )
                }
            }
            return NavSettings(
                broadcastLevel = o.optInt("broadcastLevel", LEVEL_NORMAL),
                arriveAutoExit = o.optBoolean("arriveAutoExit", true),
                keepScreenOn = o.optBoolean("keepScreenOn", true),
                avoidJam = o.optBoolean("avoidJam", true),
                autoZoom = o.optBoolean("autoZoom", true),
                favorites = favs,
                history = hist,
            )
        }

        private fun serialize(s: NavSettings): String {
            val o = JSONObject()
            o.put("broadcastLevel", s.broadcastLevel)
            o.put("arriveAutoExit", s.arriveAutoExit)
            o.put("keepScreenOn", s.keepScreenOn)
            o.put("avoidJam", s.avoidJam)
            o.put("autoZoom", s.autoZoom)
            o.put("favorites", JSONArray().apply {
                for (f in s.favorites) {
                    put(JSONObject().apply {
                        put("label", f.label)
                        put("address", f.address)
                        put("lat", f.lat)
                        put("lon", f.lon)
                        put("poiId", f.poiId ?: "")
                    })
                }
            })
            o.put("history", JSONArray().apply {
                for (h in s.history) {
                    put(JSONObject().apply {
                        put("name", h.name)
                        put("alias", h.alias)
                        put("lat", h.lat)
                        put("lon", h.lon)
                        put("poiId", h.poiId ?: "")
                        put("time", h.time)
                    })
                }
            })
            return o.toString()
        }

        /** 最近一次播报是否该念（按详细度过滤）。 */
        fun shouldSpeak(level: Int, text: String): Boolean {
            if (text.isBlank()) return false
            return when (level) {
                LEVEL_DETAILED -> true
                LEVEL_BRIEF -> isCritical(text)
                else -> !isNoise(text)
            }
        }

        /** 简洁模式：只保留转向/到达/重算这类关键信息。 */
        private fun isCritical(t: String): Boolean = Regex(
            "(左转|右转|掉头|靠左|靠右|进入|到达|重新规划|换路|出口|上桥|下桥)",
        ).containsMatchIn(t)

        /** 噪音：高德会播一些高频但不重要的提示。 */
        private fun isNoise(t: String): Boolean = Regex(
            "(注意行人|注意落石|前方有测速|请按限速行驶|保持车距|注意安全)",
        ).containsMatchIn(t)
    }

    data class Favorite(
        val label: String,
        val address: String,
        val lat: Double,
        val lon: Double,
        val poiId: String? = null,
    )

    data class HistoryEntry(
        val name: String,
        val alias: String,
        val lat: Double,
        val lon: Double,
        val poiId: String? = null,
        val time: Long = 0L,
    )

    data class NavSettings(
        /** 播报详细度：0=详细 1=标准 2=简洁 */
        val broadcastLevel: Int = LEVEL_NORMAL,
        /** 到达后自动退出导航界面 */
        val arriveAutoExit: Boolean = true,
        /** 导航时屏幕常亮 */
        val keepScreenOn: Boolean = true,
        /** 躲避拥堵 */
        val avoidJam: Boolean = true,
        /** 自动缩放 */
        val autoZoom: Boolean = true,
        val favorites: List<Favorite> = emptyList(),
        val history: List<HistoryEntry> = emptyList(),
    )
}
