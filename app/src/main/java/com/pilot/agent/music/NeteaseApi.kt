package com.pilot.agent.music

import com.pilot.agent.Dbg
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * 网易云音乐 API 客户端（自建代理 `NeteaseCloudMusicApi`）。
 *
 * 为什么自建：网易云没有面向个人开发者的公开 SDK，deep link 只能"打开 App 搜到歌"，
 * 不能直接播。自建代理能拿到播放地址，才能做到「说一句就放」。
 *
 * ## 登录
 * 走扫码登录，cookie 存在手机本地（不落服务器）：
 * ```
 * /login/qr/key           → unikey
 * /login/qr/create?qrimg  → base64 二维码
 * /login/qr/check         → 800 过期 / 801 待扫 / 802 待确认 / 803 成功（返回 cookie）
 * ```
 * 不登录只能播 30 秒试听，登录后 VIP 歌也能完整播。
 */
object NeteaseApi {

    private const val TAG = "PilotMusic"

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    data class Song(
        val id: Long,
        val name: String,
        val artist: String,
        val durationMs: Long,
    ) {
        val display: String get() = "$name - $artist"
    }

    enum class QrState { EXPIRED, WAITING_SCAN, WAITING_CONFIRM, SUCCESS, ERROR }

    // ------------------------------------------------------------ 基础

    /**
     * @param cookie 登录态。**必须走 header，不能塞进 query 参数**——
     *   cookie 有两千多字符，URL 编码后请求行过长，中间任何一层（网关/代理/OkHttp 自己）
     *   截断都会变成"未登录"，而报错信息看起来像风控，极难排查。
     */
    private fun get(
        base: String,
        path: String,
        params: Map<String, String> = emptyMap(),
        cookie: String? = null,
    ): JSONObject? {
        val query = params.entries.joinToString("&") {
            "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}"
        }
        val url = base.trimEnd('/') + path + if (query.isEmpty()) "" else "?$query"
        return try {
            val builder = Request.Builder().url(url).get()
            if (!cookie.isNullOrBlank()) builder.header("Cookie", cookie)
            val req = builder.build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Dbg.w(TAG, "HTTP ${resp.code} $path ${body.take(120)}")
                    return null
                }
                JSONObject(body)
            }
        } catch (t: Throwable) {
            Dbg.e(TAG, "请求失败 $path: ${t.message}")
            null
        }
    }

    // ------------------------------------------------------------ 搜索

    fun search(base: String, keyword: String, limit: Int = 5, cookie: String? = null): List<Song> {
        val params = mapOf("keywords" to keyword, "limit" to limit.toString())
        val json = get(base, "/search", params, cookie) ?: return emptyList()
        val songs = json.optJSONObject("result")?.optJSONArray("songs") ?: return emptyList()
        val out = ArrayList<Song>(songs.length())
        for (i in 0 until songs.length()) {
            val s = songs.optJSONObject(i) ?: continue
            val artists = s.optJSONArray("artists")
            val artistName = if (artists != null && artists.length() > 0) {
                artists.optJSONObject(0)?.optString("name").orEmpty()
            } else {
                ""
            }
            out += Song(
                id = s.optLong("id"),
                name = s.optString("name"),
                artist = artistName,
                durationMs = s.optLong("duration"),
            )
        }
        Dbg.i(TAG, "搜索「$keyword」→ ${out.size} 首${out.firstOrNull()?.let { "，首选 ${it.display}" } ?: ""}")
        return out
    }

    /**
     * 取播放地址。
     *
     * ★ 用**旧接口** `/song/url`，不要用 `/song/url/v1`：
     *   实测同一台服务器、同一个 cookie，v1 恒定返回 `-460 网络环境存在风险`，
     *   旧接口正常。这不是 IP 被风控——换接口就好，v1 大概是加了新的风控校验。
     */
    fun playUrl(base: String, songId: Long, cookie: String? = null): String? {
        val params = mapOf("id" to songId.toString())
        val json = get(base, "/song/url", params, cookie) ?: return null
        val data = json.optJSONArray("data") ?: return null
        val url = data.optJSONObject(0)?.optString("url").orEmpty()
        if (url.isBlank()) {
            // 空 url 通常是版权/试听限制，把 code 记下来方便排查
            val code = data.optJSONObject(0)?.optInt("code", -1) ?: -1
            Dbg.w(TAG, "取播放地址为空（code=$code）——可能是没登录或 VIP 限制")
            return null
        }
        Dbg.i(TAG, "取到播放地址 ${url.take(60)}…")
        return url
    }

    // ------------------------------------------------------------ 扫码登录

    fun qrKey(base: String): String? {
        val json = get(base, "/login/qr/key") ?: return null
        return json.optJSONObject("data")?.optString("unikey")?.takeIf { it.isNotBlank() }
    }

    /** @return base64 的 data:image/png;base64,... */
    fun qrImage(base: String, key: String): String? {
        val json = get(base, "/login/qr/create", mapOf("key" to key, "qrimg" to "true")) ?: return null
        return json.optJSONObject("data")?.optString("qrimg")?.takeIf { it.isNotBlank() }
    }

    /**
     * 轮询扫码结果。
     * @return Pair(状态, cookie)。成功时 cookie 非空，需要持久化到本地。
     */
    fun qrCheck(base: String, key: String): Pair<QrState, String?> {
        val json = get(base, "/login/qr/check", mapOf("key" to key)) ?: return QrState.ERROR to null
        val code = json.optInt("code", -1)
        val cookie = json.optString("cookie").takeIf { it.isNotBlank() }
        val state = when (code) {
            800 -> QrState.EXPIRED
            801 -> QrState.WAITING_SCAN
            802 -> QrState.WAITING_CONFIRM
            803 -> QrState.SUCCESS
            else -> QrState.ERROR
        }
        if (state == QrState.SUCCESS) Dbg.i(TAG, "扫码登录成功")
        return state to cookie
    }

    /** 登录态检查，顺便能拿到昵称 */
    fun loginStatus(base: String, cookie: String): String? {
        val json = get(base, "/login/status", emptyMap(), cookie) ?: return null
        val profile = json.optJSONObject("data")?.optJSONObject("profile") ?: return null
        return profile.optString("nickname").takeIf { it.isNotBlank() }
    }
}
