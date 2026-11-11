package com.pilot.agent.query

import android.net.Uri
import android.text.Html
import com.pilot.agent.Dbg
import java.net.InetAddress
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import org.json.JSONObject

/** General-purpose web search via Brave Search API; API tokens are encrypted by [SecretVault]. */
class WebSearchTool : QueryTool {
    override val name = "web_search"

    private val client = OkHttpClient.Builder()
        .connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(9, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .build()

    override fun matches(goal: String): Boolean {
        val q = goal.lowercase()
        return listOf(
            "网页", "网上搜", "上网搜", "上网查", "网上查", "帮我搜", "搜一下", "搜索一下",
            "搜索网页", "查资料", "查官网", "搜索", "网页搜索",
        ).any(q::contains) ||
            (listOf("怎么用", "教程", "官网", "资料", "介绍").any(q::contains) && q.contains("？"))
    }

    override suspend fun execute(goal: String): String = withContext(Dispatchers.IO) {
        val key = SecretVault.get(KEY_NAME)
        if (key.isBlank()) {
            return@withContext "通用网页搜索需要 Brave Search API Key。请在设置 → 网络搜索中填写；实时新闻类搜索仍可直接使用。"
        }
        val query = normalize(goal)
        if (query.isBlank()) return@withContext "请补充要搜索的主题。"
        val url = "https://api.search.brave.com/res/v1/web/search?q=" +
            URLEncoder.encode(query, Charsets.UTF_8.name()) + "&count=5&country=CN&search_lang=zh-hans&extra_snippets=false"
        try {
            val req = Request.Builder().url(url)
                .header("X-Subscription-Token", key)
                .header("Accept", "application/json")
                .header("User-Agent", "PilotAssistant/1.0 (Android)")
                .get().build()
            client.newCall(req).execute().use { response ->
                if (!response.isSuccessful) {
                    Dbg.w(TAG, "search HTTP ${response.code}")
                    return@withContext when (response.code) {
                        401, 403 -> "网页搜索密钥无效或没有 API 权限，请检查网络搜索设置。"
                        429 -> "网页搜索服务繁忙或额度已用完，请稍后重试。"
                        else -> "网页搜索暂时不可用（HTTP ${response.code}）。"
                    }
                }
                val json = JSONObject(response.body?.string().orEmpty())
                val results = json.optJSONObject("web")?.optJSONArray("results")
                if (results == null || results.length() == 0) return@withContext "没有找到“$query”的网页结果。"
                val count = results.length().coerceAtMost(MAX_RESULTS)
                val pageTool = WebPageTool()
                val articleBodies = coroutineScope {
                    (0 until count.coerceAtMost(MAX_ARTICLE_FETCHES)).map { i ->
                        val item = results.optJSONObject(i)
                        val pageUrl = item?.optString("url").orEmpty()
                        async(Dispatchers.IO) {
                            if (pageUrl.startsWith("https://")) pageTool.extractArticle(pageUrl, ARTICLE_TEXT_LIMIT)
                            else "正文抓取失败：结果没有 HTTPS 原文链接。"
                        }
                    }.awaitAll()
                }
                buildString {
                    append("网页搜索：").append(query).append("（").append(count).append(" 条）")
                    for (i in 0 until count) {
                        val item = results.optJSONObject(i) ?: continue
                        val title = item.optString("title").take(180)
                        val pageUrl = item.optString("url")
                        val description = item.optString("description").take(360)
                        append("\n\n").append(i + 1).append(". ").append(title)
                        (item.optString("age").ifBlank { item.optString("page_age") })
                            .takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
                        if (description.isNotBlank()) append("\n   ").append(description)
                        if (i < articleBodies.size) append("\n   正文摘要：").append(articleBodies[i])
                        else if (pageUrl.startsWith("https://")) append("\n   正文：未抓取（本次只展开前 ").append(MAX_ARTICLE_FETCHES).append(" 条）")
                    }
                    append("\n\n结果由 Brave Search 提供；已尝试打开前 ").append(articleBodies.size).append(" 条原文并提取正文。")
                }
            }
        } catch (t: Throwable) {
            Dbg.e(TAG, "web search failed: ${t.javaClass.simpleName}")
            "网页搜索失败，请检查网络后重试。"
        }
    }

    private fun normalize(goal: String): String {
        var q = goal.trim()
        listOf("请帮我", "帮我", "请", "在网上", "上网", "网页搜索", "搜索网页", "网页", "搜索一下", "搜一下", "搜索", "搜", "查资料", "查官网")
            .forEach { prefix -> if (q.startsWith(prefix)) q = q.removePrefix(prefix).trim() }
        return q.trim(' ', '，', ',', '。', '.', '？', '?', '！', '!')
    }

    companion object {
        private const val TAG = "PilotWebSearch"
        private const val KEY_NAME = "brave_search_api_key"
        private const val MAX_RESULTS = 5
        private const val MAX_ARTICLE_FETCHES = 3
        private const val ARTICLE_TEXT_LIMIT = 1_200
    }
}

/** Fetches a public HTTPS page and extracts readable text; private/local hosts are blocked. */
class WebPageTool : QueryTool {
    override val name = "web_page"

    private val safeDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val addresses = Dns.SYSTEM.lookup(hostname)
            require(addresses.isNotEmpty() && addresses.all(::isPublicAddress)) { "private or local host" }
            return addresses
        }
    }

    private val client = OkHttpClient.Builder()
        .dns(safeDns)
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .build()

    override fun matches(goal: String): Boolean =
        goal.contains("https://", ignoreCase = true) &&
            listOf("打开", "浏览", "总结", "阅读", "读一下", "看看这个网页", "这篇文章").any(goal::contains)

    override suspend fun execute(goal: String): String = withContext(Dispatchers.IO) {
        val raw = Regex("https://[^\\s<>\"']+", RegexOption.IGNORE_CASE).find(goal)?.value
            ?: return@withContext "请提供 HTTPS 网页链接。"
        var url = raw.trimEnd('，', ',', '。', '.', ')', '）')
        val isOpenOnly = listOf("打开", "浏览").any(goal::contains) &&
            listOf("总结", "阅读", "读一下", "提取", "概括").none(goal::contains)
        if (isOpenOnly) {
            return@withContext runCatching {
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                com.pilot.agent.PilotApp.instance.startActivity(intent)
                "已在浏览器中打开网页。"
            }.getOrElse { "无法打开这个网页链接。" }
        }
        extractArticle(url, MAX_TEXT)
    }

    /** Fetch the result URL itself (including safe public redirects) and return readable article text. */
    suspend fun extractArticle(rawUrl: String, maxChars: Int = 1_200): String = withContext(Dispatchers.IO) {
        var url = rawUrl.trim()
        repeat(4) { hop ->
            val uri = runCatching { Uri.parse(url) }.getOrNull()
                ?: return@withContext "正文抓取失败：链接格式不正确。"
            if (uri.scheme != "https" || uri.host.isNullOrBlank()) return@withContext "正文抓取失败：只允许 HTTPS 链接。"
            try {
                val request = Request.Builder().url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36")
                    .header("Accept", "text/html,application/xhtml+xml")
                    .get().build()
                client.newCall(request).execute().use { response ->
                    if (response.code in 300..399) {
                        val location = response.header("Location") ?: return@withContext "正文抓取失败：重定向地址缺失。"
                        if (hop == 3) return@withContext "正文抓取失败：重定向次数过多。"
                        url = response.request.url.resolve(location)?.toString()
                            ?: return@withContext "正文抓取失败：重定向地址无效。"
                        return@use
                    }
                    if (!response.isSuccessful) return@withContext "正文抓取失败（HTTP ${response.code}）。"
                    val type = response.body?.contentType()?.subtype.orEmpty()
                    if (type.isNotBlank() && type !in setOf("html", "xhtml+xml")) return@withContext "正文抓取失败：链接不是 HTML 网页。"
                    val source = response.body?.source() ?: return@withContext "正文抓取失败：网页没有返回内容。"
                    val buffer = Buffer()
                    while (buffer.size <= MAX_BYTES) {
                        val remaining = MAX_BYTES + 1L - buffer.size
                        val read = source.read(buffer, minOf(8_192L, remaining))
                        if (read == -1L) break
                    }
                    if (buffer.size > MAX_BYTES) return@withContext "正文抓取失败：网页内容超过读取上限。"
                    val bytes = buffer.readByteArray()
                    val html = String(bytes, response.body?.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8)
                    val title = Regex("(?is)<title[^>]*>(.*?)</title>").find(html)?.groupValues?.get(1)
                        ?.let(::plainText).orEmpty()
                    val meta = Regex("(?is)<meta[^>]+(?:name|property)=[\"'](?:description|og:description)[\"'][^>]+content=[\"'](.*?)[\"'][^>]*>")
                        .find(html)?.groupValues?.get(1)?.let(::plainText).orEmpty()
                    val content = html
                        .replace(Regex("(?is)<(script|style|noscript|svg|nav|footer|header|form)[^>]*>.*?</\\1>"), " ")
                        .replace(Regex("(?is)<(br|/p|/div|/li|/h[1-6]|/article|/section)[^>]*>"), "\n")
                    val text = plainText(content).replace(Regex("[\\t\\x0B\\f\\r ]+"), " ")
                        .replace(Regex(" *\\n *"), "\n").trim()
                    val excerpt = text.take(maxChars.coerceIn(300, MAX_TEXT))
                    return@withContext if (excerpt.isBlank()) "正文抓取失败：页面没有可读正文（可能需要登录或由脚本动态加载）。" else excerpt
                }
            } catch (t: Throwable) {
                Dbg.w("PilotWebPage", "fetch failed: ${t.javaClass.simpleName}")
                return@withContext "正文抓取失败：${t.javaClass.simpleName}。"
            }
        }
        "正文抓取失败：重定向处理失败。"
    }

    private fun plainText(value: String): String = Html.fromHtml(value, Html.FROM_HTML_MODE_COMPACT)
        .toString().replace(Regex("\\s+"), " ").trim()

    private fun isPublicAddress(ip: InetAddress): Boolean =
        !ip.isAnyLocalAddress && !ip.isLoopbackAddress && !ip.isLinkLocalAddress &&
            !ip.isSiteLocalAddress && !ip.isMulticastAddress

    private companion object {
        const val MAX_BYTES = 1_500_000
        const val MAX_TEXT = 8_000
        const val ARTICLE_TEXT_LIMIT = 1_200
    }
}
