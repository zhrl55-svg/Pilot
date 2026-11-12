package com.pilot.agent.music

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.pilot.agent.Dbg
import com.pilot.agent.PilotApp
import com.pilot.agent.agent.AgentSettings
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
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

    data class Profile(val userId: Long, val nickname: String)

    data class LyricLine(val timeMs: Long, val text: String)

    enum class QrState { EXPIRED, WAITING_SCAN, WAITING_CONFIRM, SUCCESS, NETWORK_ERROR, ERROR }

    data class QrSession(val baseUrl: String, val key: String)

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
        fallbackBase: String? = null,
    ): JSONObject? {
        val query = params.entries.joinToString("&") {
            "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}"
        }
        val url = base.trimEnd('/') + path + if (query.isEmpty()) "" else "?$query"
        val primary = base.trim().trimEnd('/')
        val fallback = (fallbackBase ?: AgentSettings.load().musicFallbackUrl).trim().trimEnd('/')
        val suffix = url.removePrefix(primary)
        val candidates = routeOrder(PilotApp.instance, primary, fallback)
        for (candidate in candidates) {
            val candidateUrl = candidate + suffix
            val response = request(candidateUrl, cookie)
            if (!response.optBoolean("ok")) {
                Dbg.w(TAG, "API 地址不可用 $candidate：${response.optString("error")}")
                continue
            }
            val code = response.optInt("code", 200)
            val body = response.optString("body")
            if (code !in 200..299) {
                Dbg.w(TAG, "HTTP $code $path ${body.take(120)}")
                return null // 应用层错误不因切换地址而重试
            }
            Dbg.i(TAG, "音乐 API 请求成功：$candidate")
            return runCatching { JSONObject(body) }
                .onFailure { Dbg.w(TAG, "API 返回非 JSON $path: ${it.message}") }
                .getOrNull()
        }
        Dbg.e(TAG, "所有音乐 API 地址均不可用：$path")
        return null
    }

    private fun request(url: String, cookie: String?): JSONObject = try {
        val host = java.net.URI(url).host.orEmpty()
        if (TailscaleGateway.isTailnetHost(host)) {
            TailscaleGateway.get(PilotApp.instance, url, cookie, 15_000)
        } else {
            val builder = Request.Builder().url(url).get()
            if (!cookie.isNullOrBlank()) builder.header("Cookie", cookie)
            client.newCall(builder.build()).execute().use { resp ->
                JSONObject().put("ok", true).put("code", resp.code).put("body", resp.body?.string().orEmpty())
            }
        }
    } catch (t: Throwable) {
        JSONObject().put("ok", false).put("error", t.message ?: "network error")
    }

    /** Home Wi-Fi should use the low-latency LAN endpoint; mobile data uses the Tailnet endpoint. */
    private fun routeOrder(context: Context, primary: String, fallback: String): List<String> {
        if (fallback.isBlank() || fallback == primary) return listOf(primary)
        val primaryTailnet = TailscaleGateway.isTailnetHost(runCatching { java.net.URI(primary).host.orEmpty() }.getOrDefault(""))
        val fallbackTailnet = TailscaleGateway.isTailnetHost(runCatching { java.net.URI(fallback).host.orEmpty() }.getOrDefault(""))
        val wifi = runCatching {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }.getOrDefault(false)
        val useFallbackFirst = (wifi && primaryTailnet && !fallbackTailnet) ||
            (!wifi && !primaryTailnet && fallbackTailnet)
        return if (useFallbackFirst) listOf(fallback, primary) else listOf(primary, fallback)
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

    /** 网易云“最近播放”。该接口需要登录账号的 uid；结果为空时由调用方回退到本地播放历史。 */
    fun recentSongs(base: String, cookie: String, limit: Int = 20): List<Song> {
        if (cookie.isBlank()) return emptyList()
        val profile = loginProfile(base, cookie) ?: return emptyList()
        val json = get(
            base,
            "/user/record",
            mapOf("uid" to profile.userId.toString(), "type" to "1"),
            cookie,
        ) ?: return emptyList()
        val all = json.optJSONObject("data")?.optJSONArray("allData") ?: return emptyList()
        return parseSongArray(all, limit, allowWrappedSong = true)
            .also { Dbg.i(TAG, "网易云最近播放：${it.size} 首") }
    }

    /** 网易云官方榜榜单。3778678=热歌榜，3779629=新歌榜，19723756=飙升榜。 */
    fun chartSongs(
        base: String,
        chartId: Long,
        cookie: String? = null,
        limit: Int = 20,
    ): List<Song> {
        val json = get(base, "/playlist/detail", mapOf("id" to chartId.toString()), cookie) ?: return emptyList()
        val tracks = json.optJSONObject("playlist")?.optJSONArray("tracks") ?: return emptyList()
        return parseSongArray(tracks, limit)
            .also { Dbg.i(TAG, "榜单 $chartId：${it.size} 首") }
    }

    fun everydayRecommendations(base: String, cookie: String, limit: Int = 20): List<Song> {
        if (cookie.isBlank()) return emptyList()
        val json = get(base, "/recommend/songs", emptyMap(), cookie) ?: return emptyList()
        val songs = json.optJSONObject("data")?.optJSONArray("dailySongs") ?: return emptyList()
        return parseSongArray(songs, limit)
            .also { Dbg.i(TAG, "每日推荐：${it.size} 首") }
    }

    fun likedSongs(base: String, cookie: String, limit: Int = 20): List<Song> {
        if (cookie.isBlank()) return emptyList()
        val profile = loginProfile(base, cookie) ?: return emptyList()
        val list = get(
            base,
            "/likelist",
            mapOf("uid" to profile.userId.toString()),
            cookie,
        ) ?: return emptyList()
        val ids = list.optJSONArray("ids")
            ?: list.optJSONObject("data")?.optJSONArray("ids")
            ?: return emptyList()
        val selected = buildList {
            for (i in 0 until minOf(ids.length(), limit)) {
                add(ids.optLong(i))
            }
        }.filter { it > 0L }
        if (selected.isEmpty()) return emptyList()
        val detail = get(
            base,
            "/song/detail",
            mapOf("ids" to selected.joinToString(",")),
            cookie,
        ) ?: return emptyList()
        val songs = detail.optJSONArray("songs") ?: return emptyList()
        return parseSongArray(songs, limit)
            .also { Dbg.i(TAG, "我喜欢的音乐：${it.size} 首") }
    }

    private fun parseSongArray(
        array: JSONArray,
        limit: Int,
        allowWrappedSong: Boolean = false,
    ): List<Song> {
        val out = ArrayList<Song>(minOf(array.length(), limit))
        for (i in 0 until array.length()) {
            if (out.size >= limit) break
            val item = array.optJSONObject(i) ?: continue
            val song = if (allowWrappedSong) item.optJSONObject("song") ?: item else item
            val id = song.optLong("id")
            val name = song.optString("name").trim()
            if (id <= 0L || name.isBlank()) continue
            val artists = song.optJSONArray("ar") ?: song.optJSONArray("artists")
            val artist = buildList {
                if (artists != null) {
                    for (index in 0 until artists.length()) {
                        val name = artists.optJSONObject(index)?.optString("name").orEmpty().trim()
                        if (name.isNotBlank()) add(name)
                    }
                }
            }.joinToString("/")
            val duration = song.optLong("dt").takeIf { it > 0L }
                ?: song.optLong("duration")
            out += Song(id, name, artist, duration)
        }
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

    /**
     * 取逐行同步歌词。优先使用逐字歌词接口，内容为空时回退到普通 LRC。
     * 制片信息（作词、作曲等）会在前奏阶段停留很久，因此从时间轴中移除。
     */
    fun lyrics(base: String, songId: Long, cookie: String? = null): List<LyricLine> {
        val json = get(
            base,
            "/lyric",
            mapOf("id" to songId.toString()),
            cookie,
        ) ?: return emptyList()
        val raw = json.optJSONObject("klyric")?.optString("lyric").orEmpty()
            .ifBlank { json.optJSONObject("lrc")?.optString("lyric").orEmpty() }
        val parsed = parseLrc(raw)
        Dbg.i(TAG, "歌词解析完成：id=$songId lines=${parsed.size}")
        return parsed
    }

    private fun parseLrc(raw: String): List<LyricLine> {
        if (raw.isBlank()) return emptyList()
        val out = sortedMapOf<Long, String>()
        raw.lineSequence().forEach { rawLine ->
            val tags = lyricTimeTag.findAll(rawLine).toList()
            if (tags.isEmpty()) return@forEach
            val text = rawLine.replace(lyricTimeTag, "").trim()
            if (text.isBlank()) return@forEach
            tags.forEach { match ->
                val minutes = match.groupValues[1].toLongOrNull() ?: return@forEach
                val seconds = match.groupValues[2].toLongOrNull() ?: return@forEach
                val fraction = match.groupValues[3]
                val millis = when (fraction.length) {
                    0 -> 0L
                    1 -> fraction.toLong() * 100L
                    2 -> fraction.toLong() * 10L
                    else -> fraction.take(3).toLong()
                }
                val timeMs = (minutes * 60L + seconds) * 1000L + millis
                if (timeMs >= 10_000L || !lyricCreditTag.containsMatchIn(text)) {
                    out[timeMs] = text
                }
            }
        }
        return out.map { (timeMs, text) -> LyricLine(timeMs, text) }
    }

    private val lyricTimeTag = Regex("""\[(\d{1,3}):([0-5]?\d)(?:[.:](\d{1,3}))?]""")
    private val lyricCreditTag = Regex(
        """^(作词|作曲|编曲|制作人|混音|母带|录音|监制|吉他|贝斯|鼓|键盘|弦乐|出品|发行|词|曲)\s*[:：]""",
        RegexOption.IGNORE_CASE,
    )

    // ------------------------------------------------------------ 扫码登录

    fun qrKey(base: String, fallbackBase: String? = null): String? {
        return qrSession(base, fallbackBase)?.key
    }

    /** The key belongs to the API instance that created it; pin every step to that same base URL. */
    fun qrSession(base: String, fallbackBase: String? = null): QrSession? {
        val primary = base.trim().trimEnd('/')
        val fallback = fallbackBase?.trim()?.trimEnd('/').orEmpty()
        for (candidate in routeOrder(PilotApp.instance, primary, fallback)) {
            val response = request("$candidate/login/qr/key?timestamp=${System.currentTimeMillis()}", null)
            if (!response.optBoolean("ok") || response.optInt("code", -1) !in 200..299) continue
            val json = runCatching { JSONObject(response.optString("body")) }.getOrNull() ?: continue
            val key = json.optJSONObject("data")?.optString("unikey")?.takeIf { it.isNotBlank() }
            if (key != null) {
                Dbg.i(TAG, "扫码会话创建成功，固定 API 地址：$candidate")
                return QrSession(candidate, key)
            }
        }
        return null
    }

    /** @return base64 的 data:image/png;base64,... */
    fun qrImage(base: String, key: String): String? {
        val json = get(
            base,
            "/login/qr/create",
            mapOf("key" to key, "qrimg" to "true", "timestamp" to System.currentTimeMillis().toString()),
            fallbackBase = "",
        ) ?: return null
        return json.optJSONObject("data")?.optString("qrimg")?.takeIf { it.isNotBlank() }
    }

    /**
     * 轮询扫码结果。
     * @return Pair(状态, cookie)。成功时 cookie 非空，需要持久化到本地。
     */
    fun qrCheck(base: String, key: String): Pair<QrState, String?> {
        val json = get(
            base,
            "/login/qr/check",
            mapOf("key" to key, "timestamp" to System.currentTimeMillis().toString()),
            fallbackBase = "",
        )
            ?: return QrState.NETWORK_ERROR to null
        val code = json.optInt("code", -1)
        val cookie = sanitizeCookie(json.optString("cookie")).takeIf { it.isNotBlank() }
        val state = when (code) {
            800 -> QrState.EXPIRED
            801 -> QrState.WAITING_SCAN
            802 -> QrState.WAITING_CONFIRM
            803 -> QrState.SUCCESS
            else -> QrState.ERROR
        }
        Dbg.i(TAG, "扫码轮询响应 code=$code state=$state cookie=${if (cookie.isNullOrBlank()) "empty" else "present"}")
        return state to cookie
    }

    /** The API may append Set-Cookie attributes to its cookie field; only send actual cookie pairs. */
    private fun sanitizeCookie(raw: String): String = raw.split(';')
        .map(String::trim)
        .filter { pair ->
            val separator = pair.indexOf('=')
            if (separator <= 0) return@filter false
            val name = pair.substring(0, separator).trim().lowercase(Locale.ROOT)
            name !in setOf("path", "expires", "max-age", "domain", "samesite", "secure", "httponly")
        }
        .joinToString("; ")

    /** 登录态检查，顺便能拿到昵称 */
    fun loginStatus(base: String, cookie: String): String? {
        return loginProfile(base, cookie)?.nickname?.takeIf { it.isNotBlank() }
    }

    fun loginProfile(base: String, cookie: String): Profile? {
        if (cookie.isBlank()) return null
        val json = get(base, "/login/status", emptyMap(), cookie) ?: return null
        val data = json.optJSONObject("data") ?: return null
        val profile = data.optJSONObject("profile")
        val account = data.optJSONObject("account")
        val userId = account?.optLong("id")?.takeIf { it > 0L }
            ?: profile?.optLong("userId")?.takeIf { it > 0L }
            ?: return null
        return Profile(userId, profile?.optString("nickname").orEmpty())
    }
}
