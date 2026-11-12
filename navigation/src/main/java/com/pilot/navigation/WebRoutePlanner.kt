package com.pilot.navigation

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Web API 路径规划（不走高德 SDK 算路）。
 *
 * ## ★ 为什么必须用 Web API 而不是 SDK 算路
 * 高德 **Android 平台 Key 绑包名+签名**。我们的包名是 `com.pilot.agent.debug`，
 * 而这个 Key 注册在 `com.navagent` 名下——SDK 算路直接被拒：
 *
 *     算路 38.944205,100.416897 → 38.924813,100.427326 ok=true
 *     → 10 秒后：算路失败 errorCode=3
 *
 * 同一个 Web Key 走 REST 就完全正常（实测返回 3.2 公里路线、逐步指令齐全）。
 * 所以路线规划改走 Web API，**引导播报自己算**——反正我们本来就要接管播报
 * （SDK 内置语音要关掉，统一走主进程的 TTS 队列）。
 *
 * ## 引导播报怎么算
 * Web API 的 `steps` 里有每一步的 `instruction`（如「向西行驶51米右转」）、
 * `distance`（米）、`duration`（秒）、`road`。我们按
 * 「进入某一步时播 + 距离递减到阈值再播下一条」的方式生成播报，
 * 简化版：每一步开始时播该步的 instruction，重要转折提前 200 米提醒。
 */
class WebRoutePlanner {

    companion object {
        private const val BASE_DRIVE = "https://restapi.amap.com/v3/direction/driving"
        private const val BASE_WALK = "https://restapi.amap.com/v3/direction/walking"
        private const val TAG = "PilotNavRoute"

        /** 播报「即将转弯」的提前量（米） */
        const val TURN_AHEAD_METERS = 200
    }

    data class Step(
        val instruction: String,
        val distance: Int,
        val duration: Int,
        val road: String,
        val polyline: List<String>,
    )

    data class Route(
        val totalDistance: Int,
        val totalDuration: Int,
        val steps: List<Step>,
    )

    /**
     * 算路。
     * @param onResult 成功回调（子线程）
     * @param onError 失败回调
     */
    fun plan(
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
        strategy: Int = 0,
        walking: Boolean = false,
        avoidCongestion: Boolean = true,
        onResult: (Route) -> Unit,
        onError: (String) -> Unit,
    ) {
        val tail = buildString {
            append("?key=").append(NavKeys.webKey())
            // 高德的经纬度顺序是「经度,纬度」
            append("&origin=").append("$fromLon,$fromLat")
            append("&destination=").append("$toLon,$toLat")
            if (!walking) {
                // Web v3: 0=速度优先，3=距离优先，4=躲避拥堵。
                val webStrategy = when (strategy) {
                    2 -> 3
                    1 -> 4
                    3 -> if (avoidCongestion) 20 else 19
                    else -> if (avoidCongestion) 4 else 0
                }
                append("&strategy=").append(webStrategy)
            }
            append("&extensions=base")
        }
        val base = if (walking) BASE_WALK else BASE_DRIVE
        val json = try {
            val conn = URL(base + tail).openConnection() as HttpURLConnection
            conn.connectTimeout = 6000
            conn.readTimeout = 8000
            conn.requestMethod = "GET"
            conn.inputStream.use { JSONObject(it.readBytes().decodeToString()) }
        } catch (t: Throwable) {
            NLog.w(TAG, "算路 HTTP 失败 ${t.message}")
            onError("算路请求失败")
            return
        }
        if (json.optString("status") != "1") {
            val msg = "算路失败（${json.optString("infocode")}）"
            NLog.w(TAG, msg)
            onError(msg)
            return
        }
        // 详细记录：高德失败时会把原因放在 info/infocode，不能只看 status
        NLog.i(TAG, "算路原始响应 status=${json.optString("status")} info=${json.optString("info")} code=${json.optString("infocode")}")
        val routeObj = json.optJSONObject("route")
        if (routeObj == null) {
            NLog.w(TAG, "响应里没有 route 字段：${json.toString().take(300)}")
            onError("没有找到路线（${json.optString("info")}）")
            return
        }
        val path = routeObj.optJSONArray("paths")?.optJSONObject(0)
        if (path == null) {
            NLog.w(TAG, "route 里没有 paths")
            onError("没有找到路线")
            return
        }
        val steps = ArrayList<Step>()
        val stepsArr = path.optJSONArray("steps")
        for (i in 0 until (stepsArr?.length() ?: 0)) {
            val s = stepsArr!!.optJSONObject(i) ?: continue
            steps.add(
                Step(
                    instruction = s.optString("instruction").trim(),
                    distance = s.optString("distance").toIntOrNull() ?: 0,
                    duration = s.optString("duration").toIntOrNull() ?: 0,
                    road = s.optString("road"),
                    polyline = s.optString("polyline").split(";").filter { it.isNotBlank() },
                ),
            )
        }
        val route = Route(
            totalDistance = path.optString("distance").toIntOrNull() ?: 0,
            totalDuration = path.optString("duration").toIntOrNull() ?: 0,
            steps = steps,
        )
        NLog.i(
            TAG,
            "${if (walking) "步行" else "驾车"}算路成功 " +
                "${route.totalDistance}m/${route.totalDuration}s ${steps.size} 步；" +
                "首步=${steps.firstOrNull()?.instruction}",
        )
        onResult(route)
    }
}
