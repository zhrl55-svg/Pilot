package com.pilot.navigation

import android.content.Context
import android.util.Log
import com.amap.api.services.core.PoiItem
import com.amap.api.services.poisearch.PoiResult
import com.amap.api.services.poisearch.PoiSearch

/**
 * SDK 版 POI 搜索（走 Android 平台 Key，认包名+签名）。
 *
 * ## 为什么要有这个（Web API 在设备上被拒）
 * 高德有两种 Key：
 * - **Android 平台 Key**（我们用的 AMAP_KEY）：给 SDK 用，认包名+签名
 * - **Web Key**（AMAP_WEB_KEY）：给 REST 用，有些是绑了 IP 白名单的
 *
 * 实测（这台设备）：Web Key 在开发机上 curl 正常，**在设备上直接
 * `INVALID_USER_KEY code=10001`**——IP 白名单不含设备出口 IP。
 * 而 Android 平台 Key + 正确的包名签名，设备上完全正常。
 * 所以设备上的地点搜索必须走 SDK 通道。
 *
 * ## nav-agent 踩过的坑（SDK 通道的 city 参数不生效）
 * nav-agent 实测：`PoiSearch.Query(keyword, "", city)` + `setCityLimit(true)`
 * **city 参数不生效**——传 city=张掖市 搜「水勘院超市」，
 * 返回的是**石家庄**的同名 POI，一路 1517 公里。
 *
 * 我们的应对：**不靠 city 参数，靠距离兜底**——
 * 1. 先用当前定位城市名做 city 偏置（能生效就用）
 * 2. 结果里按「距离」筛掉明显远的（>50km 直接丢）
 * 3. 同名候选多时，取最近的
 *
 * 这样即使 city 失效，也不会把用户导航到 1500 公里外。
 */
class SdkPoiSearch(private val context: Context) {

    companion object {
        private const val TAG = "PilotNavSdkPoi"

        /** 超过这个距离的候选直接丢（防止 city 失效导致导航到天边） */
        const val MAX_SANE_DISTANCE_M = 50_000
    }

    data class Candidate(
        val name: String,
        val address: String,
        val lat: Double,
        val lon: Double,
        val distance: Int,
        val city: String?,
    )

    /**
     * 搜索目的地。
     *
     * @param city 当前城市（偏置用，可能不生效——所以还有距离兜底）
     * @param currentLat/currentLon 当前定位（算距离）
     * @param onResult 回调（SDK 的回调在主线程）
     */
    fun search(
        keyword: String,
        city: String?,
        currentLat: Double,
        currentLon: Double,
        onResult: (List<Candidate>) -> Unit,
        onError: (String) -> Unit,
    ) {
        val q = keyword.trim()
        if (q.isEmpty()) {
            onError("目的地为空")
            return
        }
        runCatching {
            AmapNaviEngine.agreePrivacy(context)
            val query = PoiSearch.Query(q, "", city ?: "")
            query.pageNum = 1
            query.pageSize = 10
            // 城市限定打开——但**不能完全依赖它**（nav-agent 实测不生效）。
            query.setCityLimit(!city.isNullOrBlank())
            // ★ 按距离排序 + 周边搜索（nav-agent 的做法）：
            //   1. setDistanceSort(true) 让高德按远近排
            //   2. setBound(SearchBound(当前坐标, 半径, true)) 限定搜索范围
            //   这两条同时用，**绕开了「city 不生效」和「1008 检索词歧义」**：
            //   限定在当前位置周边 50km 搜，高德不会返回天边的同名 POI，
            //   也不会因为「西湖」这种通用词 nationwide 匹配太泛而报歧义。
            query.setDistanceSort(true)

            val search = PoiSearch(context.applicationContext, query)
            if (currentLat != 0.0 && currentLon != 0.0) {
                runCatching {
                    search.setBound(
                        PoiSearch.SearchBound(
                            com.amap.api.services.core.LatLonPoint(currentLat, currentLon),
                            MAX_SANE_DISTANCE_M,
                            true,
                        ),
                    )
                }.onFailure { NLog.w(TAG, "设周边范围失败 ${it.message}") }
            }
            // ★ PoiSearch 必须持有强引用——局部变量的话函数一返回就没引用了，
            //   SDK 内部弱引用会导致回调永远不来（nav-agent 明确写了这个坑）。
            lastSearch = search
            search.setOnPoiSearchListener(object : PoiSearch.OnPoiSearchListener {
                override fun onPoiSearched(result: PoiResult?, code: Int) {
                    // 1000 = 成功；1008 = 检索词歧义（高德要求更精确的词）
                    if (code != 1000) {
                        NLog.w(TAG, "POI 搜索 code=$code keyword=$q")
                    }
                    // ★ 1008 歧义 → 换更精确的词重搜一次。
                    //   「西湖」这种通用词高德会要求更精确（西湖风景区/西湖景区…）。
                    //   加「风景区」后缀能命中绝大多数景点类目的地。
                    if (code == 1008 && !retriedOnce) {
                        retriedOnce = true
                        val refined = refineKeyword(q)
                        if (refined != q) {
                            NLog.i(TAG, "关键词歧义，改用「$refined」重搜")
                            search(
                                keyword = refined,
                                city = city,
                                currentLat = currentLat,
                                currentLon = currentLon,
                                onResult = onResult,
                                onError = onError,
                            )
                            return
                        }
                    }
                    if (result == null) {
                        NLog.w(TAG, "POI 搜索失败 code=$code")
                        onError("没找到「$q」（$code）")
                        return
                    }
                    val raw = result.pois ?: emptyList()
                    val all = raw.mapNotNull { it.toCandidate() }
                    // ★ 距离兜底（关键安全阀）。
                    //
                    //   场景：用户在张掖说「导航去西湖」，张掖没有西湖，
                    //   高德会返回杭州的西湖（1500 公里外）。
                    //   这时**绝对不能**「保留最近的一个」——
                    //   那会把用户往 1500 公里外带，用户全程不知情
                    //   （nav-agent 实测踩过：搜「水勘院超市」命中石家庄，一路 1517 公里）。
                    //
                    //   正确做法：全部超出合理距离 → **报「附近没有这个地点」**，
                    //   让上层问用户是不是要去外地，而不是自作主张。
                    val near = if (currentLat != 0.0 && currentLon != 0.0) {
                        val withDist = all.map { c ->
                            val d = distanceMeters(currentLat, currentLon, c.lat, c.lon)
                            c.copy(distance = d)
                        }
                        val filtered = withDist.filter {
                            it.distance == 0 || it.distance <= MAX_SANE_DISTANCE_M
                        }
                        if (filtered.isEmpty() && withDist.isNotEmpty()) {
                            val nearestKm = (withDist.minOf { it.distance } / 1000.0)
                            NLog.w(TAG, "「$q」最近的候选在 ${nearestKm.toInt()}km 外（>${MAX_SANE_DISTANCE_M / 1000}km），判定为不在附近")
                            onError("附近没有「$q」，最近的同名地点在 ${nearestKm.toInt()} 公里外")
                            return
                        }
                        filtered
                    } else {
                        // 定位还没就绪：没法判断距离，**保守返回空**——
                        // 宁可让用户重说一次，也不要导航到未知的地方。
                        if (all.isNotEmpty()) {
                            NLog.w(TAG, "定位未就绪，无法校验「$q」的距离，暂不导航")
                            onError("定位还没准备好，稍后再试")
                            return
                        }
                        all
                    }
                    NLog.i(TAG, "POI「$q」→ ${near.size}/${all.size} 个：${near.joinToString { "${it.name}(${it.distance}m)" }}")
                    onResult(near)
                }

                override fun onPoiItemSearched(item: com.amap.api.services.core.PoiItem?, code: Int) {
                    // 单个 POI 查询，这里用不到
                }
            })
            search.searchPOIAsyn()
        }.onFailure {
            NLog.e(TAG, "POI 搜索异常", it)
            onError("搜索失败：${it.message}")
        }
    }

    private var lastSearch: PoiSearch? = null

    /** 是否已经用精细化关键词重搜过（防止无限递归）。 */
    @Volatile private var retriedOnce = false

    /**
     * 关键词精细化（应对 1008 检索词歧义）。
     *
     * 高德报 1008 = 「西湖」这种词太通用，它要求更精确。
     * 策略：加「风景区」后缀（景点类命中率最高）→ 仍不行就换回原词再试一次
     * （不同后缀匹配的是不同 POI 池）。
     */
    private fun refineKeyword(q: String): String {
        if (q.endsWith("风景区") || q.endsWith("景区")) {
            // 已经是精细词了 → 换另一种池
            return q.removeSuffix("风景区").removeSuffix("景区") + "公园"
        }
        if (q.length >= 2 && q.length <= 8) return "${q}风景区"
        return q
    }

    private fun PoiItem.toCandidate(): Candidate? {
        val ll = latLonPoint ?: return null
        val lat = ll.latitude
        val lon = ll.longitude
        if (lat == 0.0 || lon == 0.0) return null
        return Candidate(
            // SDK 的 PoiItem：名字是 title，地址是 snippet（javap 查过）
            name = title ?: "",
            address = snippet ?: "",
            lat = lat,
            lon = lon,
            distance = distance,
            city = cityName,
        )
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
