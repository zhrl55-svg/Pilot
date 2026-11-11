package com.pilot.agent.query

import android.text.Html
import android.util.Xml
import com.pilot.agent.Dbg
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser

/**
 * 实时新闻搜索。优先限定最近 24 小时，结果不足时回退到最近 7 天，并按发布时间排序。
 */
class NewsSearchTool : QueryTool {

    override val name: String = "news_search"

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val explicitHints = listOf(
        "新闻", "资讯", "消息", "动态", "热点", "头条", "最近发生", "最新情况",
        "财经", "股市新闻", "金融新闻", "娱乐", "明星", "影视", "电影资讯",
        "ai动态", "人工智能", "大模型", "科技新闻", "科技动态",
    )

    override fun matches(goal: String): Boolean {
        val q = goal.lowercase()
        return explicitHints.any { q.contains(it) } ||
            (q.contains("最新") && listOf("科技", "行业", "公司", "国际", "国内").any(q::contains))
    }

    override suspend fun execute(goal: String): String? = withContext(Dispatchers.IO) {
        val query = normalizeQuery(goal)
        if (query.isBlank()) return@withContext "请说清想搜索的主题，例如：AI 最新动态、财经新闻或娱乐新闻。"

        val now = System.currentTimeMillis()
        val dayStart = now - DAY_MS
        val recentDay = fetchFeed(query, "1d")
            .filter { it.publishedAt in dayStart..(now + FUTURE_SKEW_MS) }
            .sortedByDescending { it.publishedAt }
        Dbg.i(TAG, "freshness filter: 24h=${recentDay.size}")
        val (items, rangeLabel) = if (recentDay.size >= MAX_ITEMS) {
            recentDay.take(MAX_ITEMS) to "最近24小时"
        } else {
            val weekStart = now - WEEK_MS
            val recentWeek = fetchFeed(query, "7d")
                .filter { it.publishedAt in weekStart..(now + FUTURE_SKEW_MS) }
                .sortedByDescending { it.publishedAt }
            Dbg.i(TAG, "freshness fallback: 7d=${recentWeek.size}")
            val combined = (recentDay + recentWeek).distinctBy { it.title to it.source }
                .sortedByDescending { it.publishedAt }
                .take(MAX_ITEMS)
            combined to "最近7天（24小时内结果不足，已扩展范围）"
        }
        if (items.isEmpty()) return@withContext "最近 7 天没有找到“$query”的新闻。可以换个关键词再搜。"
        formatWithArticles(query, items, rangeLabel)
    }

    private fun fetchFeed(query: String, range: String): List<Item> {
        val encoded = URLEncoder.encode("$query when:$range", StandardCharsets.UTF_8.name())
        val url = "https://news.google.com/rss/search?q=$encoded&hl=zh-CN&gl=CN&ceid=CN:zh-Hans"
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "PilotAssistant/1.0 (Android; RSS reader)")
                .header("Accept", "application/rss+xml, application/xml, text/xml")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Dbg.w(TAG, "RSS HTTP ${response.code} range=$range")
                    emptyList()
                } else {
                    val body = response.body?.byteStream() ?: return@use emptyList()
                    body.use(::parse)
                }
            }
        } catch (t: Throwable) {
            Dbg.w(TAG, "RSS fetch failed range=$range ${t.javaClass.simpleName}")
            emptyList()
        }
    }

    private fun normalizeQuery(goal: String): String {
        var q = goal.trim()
        val prefixes = listOf(
            "帮我搜一下", "帮我搜索", "搜一下", "搜索一下", "搜索", "搜", "查一下", "查查", "查询",
            "看看", "找一下", "帮我找", "请搜", "请查", "最新的", "最新", "最近的", "最近",
        )
        prefixes.forEach { prefix ->
            if (q.startsWith(prefix, ignoreCase = true)) q = q.removePrefix(prefix).trim()
        }
        q = q.replace(Regex("最近有什么|最近发生|有什么|最新的?|最近的?|动态|资讯|新闻|消息|热点|头条"), " ")
        q = q.removeSuffix("新闻").let { base -> if (goal.contains("新闻") && base.isNotBlank()) "$base 新闻" else base }
        q = q.trim(' ', '，', ',', '。', '.', '？', '?', '！', '!')
        return q.ifBlank {
            when {
                goal.contains("AI", ignoreCase = true) || goal.contains("人工智能") -> "人工智能 AI 最新动态"
                goal.contains("娱乐") || goal.contains("明星") -> "娱乐新闻"
                goal.contains("财经") || goal.contains("金融") -> "财经新闻"
                goal.contains("国际") -> "国际新闻"
                goal.contains("国内") -> "国内新闻"
                goal.contains("新闻") || goal.contains("头条") || goal.contains("热点") -> "最新新闻"
                else -> ""
            }
        }.let { normalized ->
            when {
                normalized.equals("AI", ignoreCase = true) -> "AI 人工智能 最新动态"
                normalized.equals("财经", ignoreCase = true) -> "财经新闻"
                normalized.equals("娱乐", ignoreCase = true) -> "娱乐新闻"
                else -> normalized
            }
        }
    }

    private data class Item(val title: String, val source: String, val publishedAt: Long, val link: String, val description: String)

    private fun parse(input: java.io.InputStream): List<Item> {
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(input, null)
        }
        val items = mutableListOf<Item>()
        var event = parser.eventType
        var inItem = false
        var tag = ""
        var title = ""
        var source = ""
        var publishedAt: Long? = null
        var link = ""
        var description = ""
        while (event != XmlPullParser.END_DOCUMENT && items.size < 20) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    tag = parser.name.lowercase(Locale.ROOT)
                    if (tag == "item") {
                        inItem = true
                        title = ""; source = ""; publishedAt = null; link = ""; description = ""
                    } else if (inItem && tag == "source") {
                        source = parser.getAttributeValue(null, "url").orEmpty()
                    } else if (inItem && tag == "link" && parser.getAttributeValue(null, "href") != null) {
                        link = parser.getAttributeValue(null, "href").orEmpty()
                    }
                }
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> if (inItem) {
                    val value = parser.text.orEmpty().trim()
                    when (tag) {
                        "title" -> if (value.isNotBlank()) title = value
                        "source" -> if (value.isNotBlank()) source = value
                        "pubdate" -> if (value.isNotBlank()) publishedAt = parseDate(value)
                        "link" -> if (value.startsWith("http")) link = value
                        "description" -> if (value.isNotBlank()) description = value
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name.equals("item", ignoreCase = true)) {
                        if (title.isNotBlank() && publishedAt != null) {
                            val splitTitle = title.lastIndexOf(" - ")
                            val resolvedSource = source.ifBlank {
                                if (splitTitle > 0) title.substring(splitTitle + 3) else "新闻来源"
                            }
                            val resolvedTitle = if (source.isBlank() && splitTitle > 0) title.substring(0, splitTitle) else title
                            val cleanDescription = Html.fromHtml(description, Html.FROM_HTML_MODE_COMPACT)
                                .toString().replace(Regex("\\s+"), " ").trim().take(900)
                            items += Item(resolvedTitle, resolvedSource, publishedAt!!, link, cleanDescription)
                        }
                        inItem = false
                    }
                    tag = ""
                }
            }
            event = parser.nextToken()
        }
        return items.distinctBy { it.title to it.source }
    }

    private fun parseDate(raw: String): Long? = runCatching {
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US).apply { isLenient = false }
            .parse(raw)?.time
    }.getOrNull()

    private fun formatDate(time: Long): String {
        val age = (System.currentTimeMillis() - time).coerceAtLeast(0)
        val relative = when {
            age < HOUR_MS -> "${(age / MINUTE_MS).coerceAtLeast(1)} 分钟前"
            age < DAY_MS -> "${age / HOUR_MS} 小时前"
            else -> "${age / DAY_MS} 天前"
        }
        val absolute = SimpleDateFormat("MM-dd HH:mm", Locale.SIMPLIFIED_CHINESE).apply {
            timeZone = TimeZone.getDefault()
        }.format(time)
        return "$relative（$absolute）"
    }

    private suspend fun formatWithArticles(query: String, items: List<Item>, rangeLabel: String): String = coroutineScope {
        val pageTool = WebPageTool()
        val articleBodies = items.take(MAX_ARTICLE_FETCHES).map { item ->
            async(Dispatchers.IO) {
                if (item.link.startsWith("https://")) pageTool.extractArticle(item.link, ARTICLE_TEXT_LIMIT)
                else "正文抓取失败：没有有效原文链接。"
            }
        }.awaitAll()
        buildString {
        append("“").append(query).append("”的新闻，范围：").append(rangeLabel).append("（共 ").append(items.size).append(" 条）\n")
        items.forEachIndexed { index, item ->
            append('\n').append(index + 1).append(". ").append(item.title)
            append("\n   来源：").append(item.source)
            append(" · ").append(formatDate(item.publishedAt))
            if (index < articleBodies.size) {
                val body = articleBodies[index]
                if (body.startsWith("正文抓取失败") && item.description.isNotBlank()) {
                    append("\n   文章摘要：").append(item.description)
                    append("（原文暂时无法读取）")
                } else {
                    append("\n   正文摘要：").append(body)
                }
            }
        }
        append("\n\n来源：Google News RSS；已打开全部结果原文并提取正文摘要。")
        }
    }

    private companion object {
        const val TAG = "PilotNews"
        const val MAX_ITEMS = 5
        const val MAX_ARTICLE_FETCHES = MAX_ITEMS
        const val ARTICLE_TEXT_LIMIT = 900
        const val MINUTE_MS = 60_000L
        const val HOUR_MS = 60 * MINUTE_MS
        const val DAY_MS = 24 * HOUR_MS
        const val WEEK_MS = 7 * DAY_MS
        const val FUTURE_SKEW_MS = 5 * MINUTE_MS
    }
}
