package com.pilot.agent.query

import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.pilot.agent.Dbg
import com.pilot.agent.PilotApp
import com.pilot.navigation.GeoCoder
import com.pilot.navigation.NavKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 天气查询：走高德 Web 服务 `v3/weather/weatherInfo`，不碰屏幕、不进 AgentLoop。
 *
 * 高德天气只认 adcode（6 位），不认城市名（实测 city=张掖 返回空列表）。
 * - 用户明确说了城市 → 从内置常用表匹配 adcode；
 * - 没说城市 → 用系统定位反查 adcode（本地优先，最快）。
 *
 * 实况（base）与预报（all）都查：回答里同时给当前温度和今天高低温，
 * 一次请求循环搞定，不让 Agent 开浏览器绕 5 步。
 */
class WeatherTool : QueryTool {

    override val name: String = "weather"

    private val weatherHints = listOf("天气", "气温", "温度", "下雨", "下雨吗", "下雨了", "冷不冷", "热不热", "穿什么")
    private val forecastHints = listOf("明天", "后天", "未来", "这几天", "一周", "星期", "下周")

    override fun matches(goal: String): Boolean {
        if (weatherHints.none { goal.contains(it) }) return false
        // " vous savez 天气怎么样" 之外的纯问答也接，但排除明显的闲聊式提法
        return true
    }

    override suspend fun execute(goal: String): String = withContext(Dispatchers.IO) {
        val key = NavKeys.webKey()
        if (key.isBlank()) {
            return@withContext "天气查询还没配置高德 Web Key，请在 local.properties 里设置 AMAP_WEB_KEY。"
        }

        val cityName = cityFromGoal(goal)
        val adcode = if (cityName != null) {
            CITY_ADCODE[cityName] ?: run {
                // 表外城市：用高德 POI 搜索拿 adcode（这个 key 的 geo 接口权限不足，POI 可用）
                resolveAdcodeByPoi(key, cityName)
                    ?: return@withContext "没查到「$cityName」的天气区域码，试试换个城市名或问“今天天气”。"
            }
        } else {
            currentAdcode()
                ?: return@withContext "没拿到你的位置，没法确定查哪个城市。可以说“北京天气”这样的具体城市。"
        }

        val base = fetch(key, adcode, "base")
            ?: return@withContext "天气查询失败（实况），请稍后再试。"
        val all = if (forecastHints.any { goal.contains(it) }) fetch(key, adcode, "all") else null

        val lives = base.optJSONArray("lives")?.optJSONObject(0)
        if (lives == null) {
            Dbg.w(TAG, "天气返回空 lives adcode=$adcode")
            return@withContext "这个区域暂时没有天气数据（adcode=$adcode）。"
        }

        val city = lives.optString("city").ifBlank { cityName ?: "当前位置" }
        val now = lives.optString("weather")
        val temp = lives.optString("temperature")
        val feels = lives.optString("humidity")
        val wind = lives.optString("winddirection") + lives.optString("windpower")
        val reported = lives.optString("reporttime")

        val wantForecast = forecastHints.any { goal.contains(it) }
        val sb = StringBuilder()
        sb.append("${city}天气：$now，气温 ${temp}℃，${wind}风，湿度 ${feels}%。")

        if (all != null) {
            val casts = all.optJSONArray("forecasts")?.optJSONObject(0)?.optJSONArray("casts")
            if (casts != null && casts.length() > 0) {
                sb.append(" 预报：")
                val count = if (goal.contains("一周") || goal.contains("未来")) minOf(7, casts.length()) else minOf(3, casts.length())
                for (i in 0 until count) {
                    val c = casts.optJSONObject(i) ?: continue
                    val day = c.optString("dayweather")
                    val night = c.optString("nightweather")
                    val high = c.optString("daytemp")
                    val low = c.optString("nighttemp")
                    val whenLabel = when (i) {
                        0 -> "今天"
                        1 -> "明天"
                        2 -> "后天"
                        else -> c.optString("date").removePrefix("2026-")
                    }
                    sb.append("$whenLabel $day/$night ${low}~${high}℃；")
                }
            }
        } else {
            // 即使用户没问预报，也给今天高低温——比只报实时温度有用
            val today = fetch(key, adcode, "all")
                ?.optJSONArray("forecasts")?.optJSONObject(0)
                ?.optJSONArray("casts")?.optJSONObject(0)
            if (today != null) {
                sb.append(" 今天 ${today.optString("dayweather")}，${today.optString("nighttemp")}~${today.optString("daytemp")}℃。")
            }
        }
        sb.append("（数据更新 $reported）")
        sb.toString()
    }

    /** 从指令里抽出城市名；没明确说城市返回 null（=用定位）。 */
    private fun cityFromGoal(goal: String): String? {
        // 优先长名匹配，避免"南京"被"北京"之类短名误伤
        return CITY_ADCODE.keys
            .filter { goal.contains(it) }
            .maxByOrNull { it.length }
    }

    /** 没定位权限/没定位时返回 null，由调用方明确告知用户，而不是静默报别的城市。 */
    private fun currentAdcode(): String? {
        val ctx = PilotApp.instance
        val granted = ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Dbg.w(TAG, "没有定位权限，无法确定城市")
            return null
        }
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val loc: Location? = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .filter { it.latitude != 0.0 && it.longitude != 0.0 }
            .maxByOrNull { it.time }
        val adcode = loc?.let { GeoCoder.adcodeOf(it.latitude, it.longitude) }
        if (adcode != null) return adcode
        Dbg.w(TAG, "定位反查 adcode 失败")
        return null
    }

    /** geo 接口这个 key 无权限，用 POI 搜索兜底拿 adcode。 */
    private fun resolveAdcodeByPoi(key: String, city: String): String? {
        val url = "https://restapi.amap.com/v3/place/text?key=$key&keywords=" +
            URLEncoder.encode(city, "UTF-8") + "&offset=1&output=json"
        val json = getJson(url) ?: return null
        if (json.optString("status") != "1") return null
        val adcode = json.optJSONArray("pois")?.optJSONObject(0)
            ?.optString("adcode")
        return adcode?.takeIf { it.length == 6 && it.all(Char::isDigit) }
    }

    private fun fetch(key: String, adcode: String, extensions: String): JSONObject? {
        val url = "https://restapi.amap.com/v3/weather/weatherInfo?key=$key&city=$adcode" +
            "&extensions=$extensions&output=json"
        val json = getJson(url) ?: return null
        if (json.optString("status") != "1") {
            Dbg.w(TAG, "天气接口失败 ${json.optString("infocode")} ${json.optString("info")}")
            return null
        }
        return json
    }

    private fun getJson(url: String): JSONObject? = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 6000
        conn.readTimeout = 8000
        conn.requestMethod = "GET"
        JSONObject(conn.inputStream.use { it.readBytes().decodeToString() })
    }.getOrElse {
        Dbg.w(TAG, "天气请求异常 ${it.javaClass.simpleName}")
        null
    }

    private companion object {
        const val TAG = "PilotWeather"


        // 常用城市 adcode（高德 2026 版；完整表见 https://lbs.amap.com/api/webservice/download）
        val CITY_ADCODE = mapOf(
            "北京" to "110000", "上海" to "310000", "广州" to "440100", "深圳" to "440300",
            "天津" to "120000", "重庆" to "500000", "成都" to "510100", "杭州" to "330100",
            "南京" to "320100", "武汉" to "420100", "西安" to "610100", "郑州" to "410100",
            "长沙" to "430100", "苏州" to "320500", "青岛" to "370200", "济南" to "370100",
            "厦门" to "350200", "福州" to "350100", "昆明" to "530100", "贵阳" to "520100",
            "南宁" to "450100", "海口" to "460100", "三亚" to "460200", "合肥" to "340100",
            "南昌" to "360100", "沈阳" to "210100", "大连" to "210200", "长春" to "220100",
            "哈尔滨" to "230100", "石家庄" to "130100", "太原" to "140100", "呼和浩特" to "150100",
            "兰州" to "620100", "西宁" to "630100", "银川" to "640100", "乌鲁木齐" to "650100",
            "拉萨" to "540100", "张掖" to "620700", "酒泉" to "620900", "嘉峪关" to "620200",
            "武威" to "620600", "金昌" to "620300", "庆阳" to "621000", "天水" to "620500",
            "敦煌" to "620982", "香港" to "810000", "澳门" to "820000", "台北" to "710000",
        )
    }
}
