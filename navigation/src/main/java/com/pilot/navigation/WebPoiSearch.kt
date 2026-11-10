package com.pilot.navigation

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Web API 地点搜索（同步 HTTP，放子线程调用）。
 *
 * ## 为什么 Web API 优先（nav-agent 2026-11-04 定稿）
 * SDK 的 `PoiSearch` 有两个硬伤：
 * 1. **city 参数不生效**——传 city=张掖市 搜「水勘院超市」，
 *    返回的是**石家庄**的同名 POI，一路 1517 公里。
 * 2. **通用词报 1008 歧义**——「张掖西站」「西湖」这种词直接 code=1008，
 *    SDK 通道实测完全搜不出来。
 *
 * Web API 的 `city=当前城市` + `citylimit=false` 组合语义是对的：
 * city 只作**偏置**不作硬限制，高德自己的排序最准。
 *
 * ## 核心策略（不要自己判断地名带不带行政区划）
 * nav-agent 试过用正则判断，结果「水勘院超**市**」里的「市」被当成行政区划，
 * 跳过本地搜索直接全国搜 → 命中石家庄，一路 1517 公里。
 * 猜地名规则太脆，不如让高德自己排。
 *
 * ## 距离安全阀（老板 11-10 提醒「张掖没有西湖啊」）
 * Web API 也可能返回 1500 公里外的同名 POI。所以拿到结果后
 * **必须**按距离过滤：超过 [MAX_SANE_DISTANCE_M] 的直接丢。
 * 全被丢掉 → 报「附近没有这个地点」，而不是导航到天边。
 */
object WebPoiSearch {

    private const val BASE = "https://restapi.amap.com/v3/place/text"
    private const val TAG = "PilotNavWebPoi"

    /** 超过这个距离的候选直接丢（防止导航到 1500 公里外） */
    const val MAX_SANE_DISTANCE_M = 50_000

    /**
     * **同步**搜索（调用方自己放子线程）。
     *
     * 内部就是包一层回调版：HTTP 是同步的，onResult 在**调用线程**直接返回，
     * 所以不需要 latch。给搜索面板这种「子线程里顺序取结果」的场景用。
     */
    fun searchBlocking(
        keyword: String,
        city: String?,
        currentLat: Double,
        currentLon: Double,
        limit: Int = 10,
    ): List<SdkPoiSearch.Candidate> {
        var out: List<SdkPoiSearch.Candidate> = emptyList()
        search(
            keyword = keyword,
            city = city,
            currentLat = currentLat,
            currentLon = currentLon,
            onResult = { out = it },
            onError = { NLog.w("PilotNavWebPoi", "同步搜索失败：$it") },
        )
        return out
    }

    /**
     * 搜索地点。
     * @param city 当前城市（偏置用，可空）
     * @param onResult 回调（子线程）
     */
    fun search(
        keyword: String,
        city: String?,
        currentLat: Double,
        currentLon: Double,
        onResult: (List<SdkPoiSearch.Candidate>) -> Unit,
        onError: (String) -> Unit,
    ) {
        val q = keyword.trim()
        if (q.isEmpty()) {
            onError("目的地为空")
            return
        }
        val tail = buildString {
            append("?key=").append(NavKeys.webKey())
            append("&keywords=").append(java.net.URLEncoder.encode(q, "UTF-8"))
            // extensions=base：省 60% 流量（all 会带回 photos/featured_reviews）
            append("&extensions=base")
            append("&offset=10")
            append("&page=1")
            if (!city.isNullOrBlank()) {
                append("&city=").append(java.net.URLEncoder.encode(city, "UTF-8"))
            }
            // ★ citylimit=false：city 只作偏置不作硬限制，
            //   这样跨城地名也能搜到（citylimit=true 会一条都搜不到）。
            append("&citylimit=false")
        }
        val json = query(tail)
        if (json == null) {
            onError("地点搜索请求失败")
            return
        }
        if (json.optString("status") != "1") {
            val info = json.optString("info")
            val code = json.optString("infocode")
            NLog.w(TAG, "Web API 失败 info=$info code=$code")
            onError("地点搜索失败（$code）")
            return
        }
        val arr = json.optJSONArray("pois") ?: run {
            onError("没有搜索结果")
            return
        }
        val all = ArrayList<SdkPoiSearch.Candidate>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val location = o.optString("location")
            if (location.isBlank()) continue
            val parts = location.split(",")
            if (parts.size < 2) continue
            // Web API 的 location 是 "经度,纬度"
            val lon = parts[0].toDoubleOrNull() ?: continue
            val lat = parts[1].toDoubleOrNull() ?: continue
            all.add(
                SdkPoiSearch.Candidate(
                    // ★ POI ID：SDK 算路要用它找「正确入口」。
                    //   只用坐标的话终点可能落在停车场内部/建筑里，
                    //   SDK 报 errorCode=6 = ERROR_ENDPOINT（实测踩过）。
                    poiId = o.optString("id").takeIf { it.isNotBlank() },
                    name = o.optString("name").ifBlank { q },
                    address = listOfNotNull(
                        o.optString("pname").takeIf { it.isNotBlank() },
                        o.optString("cityname").takeIf { it.isNotBlank() },
                        o.optString("adname").takeIf { it.isNotBlank() },
                        o.optString("address").takeIf { it.isNotBlank() },
                    ).distinct().joinToString(""),
                    lat = lat,
                    lon = lon,
                    distance = o.optString("distance").toIntOrNull() ?: 0,
                    city = o.optString("cityname"),
                ),
            )
        }
        NLog.i(TAG, "Web API「$q」city=$city → ${all.size} 条")

        // ★ 距离安全阀
        if (all.isEmpty()) {
            onError("没找到「$q」")
            return
        }
        if (currentLat == 0.0 || currentLon == 0.0) {
            NLog.w(TAG, "定位未就绪，跳过距离校验（保留全部候选）")
            onResult(all)
            return
        }
        val withDist = all.map { c ->
            c.copy(distance = distanceMeters(currentLat, currentLon, c.lat, c.lon))
        }
        val near = withDist.filter { it.distance <= MAX_SANE_DISTANCE_M }
        if (near.isEmpty()) {
            val nearestKm = withDist.minOf { it.distance } / 1000.0
            NLog.w(TAG, "「$q」最近的候选在 ${nearestKm.toInt()}km 外，判定为不在附近")
            onError("附近没有「$q」，最近的同名地点在 ${nearestKm.toInt()} 公里外")
            return
        }
        onResult(near)
    }

    private fun query(tail: String): JSONObject? = try {
        val conn = URL(BASE + tail).openConnection() as HttpURLConnection
        conn.connectTimeout = 6000
        conn.readTimeout = 8000
        conn.requestMethod = "GET"
        conn.inputStream.use { JSONObject(it.readBytes().decodeToString()) }
    } catch (t: Throwable) {
        NLog.w(TAG, "Web API HTTP 失败 ${t.message}")
        null
    }

    /** 两点距离（米），Haversine。 */
    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Int {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return (r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))).toInt()
    }
}
