package com.pilot.navigation

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 逆地理编码：经纬度 → 人类可读的地址。
 *
 * ## 为什么需要
 * 常去地点、当前位置这些地方如果直接显示 `38.94478, 100.4`，
 * 用户根本不知道自己存的是哪儿（老板反馈「不要显示经纬度，要解析为最近的地址」）。
 *
 * ## 用 Web API 而不是 SDK
 * SDK 的 `GeocodeSearch` 也要 Key 且回调不稳（见 WebPoiSearch 头注释）；
 * 逆地理编码走 REST 简单可靠，和地点搜索共用同一个 Web Key。
 */
object GeoCoder {

    private const val TAG = "PilotNavGeo"
    private const val BASE = "https://restapi.amap.com/v3/geocode/regeo"

    /**
     * 反查地址（**同步**，调用方放子线程）。
     *
     * @return 形如「甘肃省张掖市甘州区南街街道县府街36号」；失败返回 null
     */
    fun reverse(context: Context, lat: Double, lon: Double): String? {
        if (lat == 0.0 || lon == 0.0) return null
        val key = NavKeys.webKey(context)
        if (key.isBlank()) {
            NLog.w(TAG, "Web Key 没配，无法反查地址")
            return null
        }
        val tail = buildString {
            append("?key=").append(key)
            // 高德的 location 是「经度,纬度」
            append("&location=").append("$lon,$lat")
            append("&extensions=base")
            append("&batch=false")
        }
        return runCatching {
            val conn = URL(BASE + tail).openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 6000
            conn.requestMethod = "GET"
            val json = conn.inputStream.use { JSONObject(it.readBytes().decodeToString()) }
            if (json.optString("status") != "1") {
                NLog.w(TAG, "反查失败 code=${json.optString("infocode")} info=${json.optString("info")}")
                return null
            }
            val re = json.optJSONObject("regeocode") ?: return null
            val formatted = re.optString("formatted_address")
            if (formatted.isNotBlank()) return formatted

            // 没给 formatted_address 就自己拼（省市区 + 街道 + 门牌）
            val comp = re.optJSONObject("addressComponent") ?: return null
            val parts = mutableListOf<String>()
            comp.optString("province").takeIf { it.isNotBlank() }?.let { parts.add(it) }
            comp.optString("city").takeIf { it.isNotBlank() && it != "[]" }?.let { parts.add(it) }
            comp.optString("district").takeIf { it.isNotBlank() }?.let { parts.add(it) }
            comp.optString("township").takeIf { it.isNotBlank() && it != "[]" }?.let { parts.add(it) }
            comp.optString("streetNumber")?.let { _ ->
                comp.optJSONObject("streetNumber")?.let { sn ->
                    sn.optString("street").takeIf { it.isNotBlank() }?.let { parts.add(it) }
                    sn.optString("number").takeIf { it.isNotBlank() }?.let { parts.add(it) }
                }
            }
            parts.joinToString("").ifBlank { null }
        }.getOrElse {
            NLog.w(TAG, "反查异常 ${it.message}")
            null
        }
    }

    /**
     * 反查城市 adcode（高德天气接口只认 adcode，不认城市名）。
     * 同步调用，调用方放子线程。失败返回 null。
     */
    fun adcodeOf(context: Context, lat: Double, lon: Double): String? {
        if (lat == 0.0 || lon == 0.0) return null
        val key = NavKeys.webKey(context)
        if (key.isBlank()) return null
        return runCatching {
            val tail = "?key=$key&location=$lon,$lat&extensions=base&batch=false"
            val conn = URL(BASE + tail).openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 6000
            conn.requestMethod = "GET"
            val json = JSONObject(conn.inputStream.use { it.readBytes().decodeToString() })
            if (json.optString("status") != "1") return null
            json.optJSONObject("regeocode")
                ?.optJSONObject("addressComponent")
                ?.optString("adcode")
                ?.takeIf { it.length == 6 && it.all(Char::isDigit) }
        }.getOrElse {
            NLog.w(TAG, "adcode 反查异常 ${it.message}")
            null
        }
    }

    /**
     * 反查地址，失败时退回「附近的路名或 POI 名」。
     * 给 UI 直接显示用，保证不会出现纯经纬度。
     */
    fun describe(context: Context, lat: Double, lon: Double, fallback: String = "当前位置"): String {
        val addr = reverse(context, lat, lon)
        if (!addr.isNullOrBlank()) return addr
        // 反查失败：用附近的 POI 名兜底
        val near = runCatching {
            WebPoiSearch.searchBlocking(context, "附近", null, lat, lon, 1).firstOrNull()
        }.getOrNull()
        return near?.name ?: fallback
    }
}
