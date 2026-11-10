package com.pilot.agent.query

import com.pilot.agent.Dbg
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * A 股指数行情（新浪免费接口，不需要 key）。
 *
 * ## 接口
 * ```
 * GET https://hq.sinajs.cn/list=s_sh000001,s_sz399001
 * → var hq_str_s_sh000001="上证指数,3421.50,21.30,0.63,1234567,12345678";
 * ```
 * 字段：名称, 当前点数, 涨跌额, 涨跌幅%, 成交量(手), 成交额(万元)
 *
 * ## 两个必须记住的坑
 * 1. **不带 Referer 会 403**：新浪对这个接口做了防盗链，必须伪装成从财经频道点进来的。
 * 2. **返回是 GBK 编码**，而且是 `var ...="..."` 的 JS 赋值语句，不是 JSON。
 *    用 UTF-8 解码会得到乱码中文，必须显式按 GBK 解。
 */
class StockTool : QueryTool {

    override val name: String = "stock"

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /** 关键词 → 新浪代码。用 s_ 前缀取简版（6 个字段），够播报用 */
    private val INDEXES: List<Pair<String, String>> = listOf(
        "上证" to "s_sh000001",
        "大盘" to "s_sh000001",
        "沪指" to "s_sh000001",
        "深证" to "s_sz399001",
        "成指" to "s_sz399001",
        "创业板" to "s_sz399006",
        "沪深300" to "s_sh000300",
        "沪深" to "s_sh000300",
    )

    private val HINTS = listOf(
        "上证", "深证", "创业板", "沪深", "大盘", "沪指",
        "A股", "a股", "指数", "行情", "股价", "涨了", "跌了",
    )

    override fun matches(goal: String): Boolean = HINTS.any { goal.contains(it) }

    override suspend fun execute(goal: String): String? = withContext(Dispatchers.IO) {
        // 提到哪个就查哪个；没提到具体指数就报三大指数
        val codes = INDEXES
            .filter { goal.contains(it.first) }
            .map { it.second }
            .ifEmpty { listOf("s_sh000001", "s_sz399001", "s_sz399006") }
            .distinct()

        val url = "https://hq.sinajs.cn/list=" + codes.joinToString(",")
        val text = try {
            val req = Request.Builder()
                .url(url)
                .header("Referer", "https://finance.sina.com.cn")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 12)")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Dbg.w(TAG, "HTTP ${resp.code}")
                    return@withContext null
                }
                // ★ GBK，不是 UTF-8
                String(resp.body?.bytes() ?: return@withContext null, Charset.forName("GBK"))
            }
        } catch (t: Throwable) {
            Dbg.e(TAG, "行情请求失败：${t.message}")
            return@withContext null
        }

        val quotes = parse(text)
        if (quotes.isEmpty()) {
            Dbg.w(TAG, "没解析出行情：${text.take(120)}")
            return@withContext null
        }
        Dbg.i(TAG, "行情 ${quotes.size} 条：${quotes.joinToString { it.name + it.changePercent }}")
        speakable(quotes)
    }

    private data class Quote(
        val name: String,
        val point: String,
        val change: String,
        val changePercent: String,
    ) {
        val isUp: Boolean get() = !change.startsWith("-")
    }

    private fun parse(raw: String): List<Quote> {
        val re = Regex("""var hq_str_s_\w+="([^"]*)"""")
        return re.findAll(raw).mapNotNull { m ->
            val f = m.groupValues[1].split(',')
            if (f.size < 4 || f[0].isBlank()) return@mapNotNull null
            Quote(
                name = f[0].trim(),
                point = f[1].trim(),
                change = f[2].trim(),
                changePercent = f[3].trim(),
            )
        }.toList()
    }

    /**
     * 转成口语。
     * 涨跌要带方向词——播报里念「0.63」用户听不出是涨还是跌。
     */
    private fun speakable(list: List<Quote>): String =
        list.joinToString("；") { q ->
            val dir = if (q.isUp) "涨" else "跌"
            // 新浪返回的点数是全精度（3663.7235），播报念 4 位小数很怪，取 2 位
            val point = q.point.toDoubleOrNull()?.let { "%.2f".format(it) } ?: q.point
            "${q.name} $point 点，$dir ${q.changePercent.removePrefix("-")}%"
        }

    private companion object {
        const val TAG = "PilotQuery"
    }
}
