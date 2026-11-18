package com.pilot.agent.assistant.routing

import java.text.Normalizer
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import org.json.JSONObject

data class SlotExtraction(
    val args: JSONObject,
    val missing: Set<String>,
    val summary: String,
    val parsedTime: ChineseTimeParser.Parsed? = null,
    val ambiguous: Boolean = false,
)

/**
 * 确定性槽位抽取器。**这个类不调用模型**。
 *
 * 措辞、路径、正文、提醒标题都取自用户原话；日期与时长来自
 * [ChineseTimeParser]。
 *
 * 移植自 Minis 的 `SlotExtractor`，按 Pilot 的 v1 能力裁剪：
 * 保留 app_launch / volume / device / media / reminder / scheduled_task /
 * memory_write / memory_get / file_read / file_write / screenshot。
 */
class SlotExtractor(
    private val nowProvider: () -> LocalDateTime = { LocalDateTime.now() },
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val pathRegex = Regex("""/(?:[A-Za-z0-9_.\-~]+/)*[A-Za-z0-9_.\-~]+""")
    private val urlRegex = Regex("""https?://\S+|\bwww\.[A-Za-z0-9.\-]+\S*""")
    private val numberIdRegex = Regex("""(?:id|编号|序号)\s*[:：]?\s*(\d+)""", RegexOption.IGNORE_CASE)

    fun extract(userText: String, route: AssistantRoute): SlotExtraction {
        val text = AssistantRouteMatcher.normalizeTextPreservingCase(userText)
        val raw = normalizeContent(userText)
        val args = JSONObject()
        val parsed = ChineseTimeParser.parse(text, nowProvider(), zone)
        val now = nowProvider()

        when (route.capability) {
            "volume" -> extractVolume(text, route.action, args)
            "media" -> extractMedia(text, route.action, args)
            "reminder" -> extractReminder(text, raw, route.action, parsed, now, args)
            "scheduled_task" -> extractScheduled(text, route.action, parsed, now, args)
            "device" -> extractDevice(text, route.action, args)
            "app_launch" -> extractApp(text, route.action, args)
            "file_read" -> pathRegex.find(text)?.let { args.put("path", it.value) }
            "file_write" -> {
                pathRegex.find(text)?.let { args.put("path", it.value) }
                extractWriteContent(raw, args)
            }
            "memory_write" -> extractAfterTrigger(
                raw,
                listOf("记住", "牢记", "记下来", "记一下", "存进记忆", "别忘了", "remember that", "remember"),
            )
                // ★ 至少两个字符 —— 与遗留匹配器（`LocalToolRuntime.matchMemory`）对齐。
                //   「别忘了说」这种一个字的尾巴遗留侧不写记忆；快路径也不该写，
                //   否则提升 memory_write 之后会凭空多出「说」这种垃圾记忆。
                ?.takeIf { it.length >= 2 }
                ?.let { args.put("content", it) }
            "memory_get" -> extractAfterTrigger(
                raw,
                listOf(
                    "我说过", "我的记忆", "之前说过", "查记忆",
                    "还记得", "你记得", "记不记得", "记忆里",
                    "what did i say", "my memory",
                ),
            )?.let { args.put("keywords", it) }
            "screenshot" -> Unit
            else -> Unit
        }

        val spec = CapabilityRegistry.action(route.capability, route.action)
        val missing = spec?.requiredSlots.orEmpty()
            .filterTo(linkedSetOf()) { args.optString(it).isBlank() }
        val ambiguousTime = parsed?.ambiguous == true ||
            (parsed != null && parsed.confidence < 0.8)
        return SlotExtraction(
            args = args,
            missing = missing,
            summary = summaryFor(route, args),
            parsedTime = parsed,
            ambiguous = ambiguousTime,
        )
    }

    private fun extractReminder(
        text: String,
        raw: String,
        action: String,
        parsed: ChineseTimeParser.Parsed?,
        now: LocalDateTime,
        args: JSONObject,
    ) {
        when (action) {
            "schedule" -> {
                if (parsed != null) {
                    if (parsed.relative) {
                        val seconds = ChineseTimeParser.secondsUntil(parsed.dateTime, now)
                        args.put("after", seconds)
                    } else {
                        args.put("at", parsed.iso())
                    }
                }
                args.put("title", reminderTitle(text))
                args.put("body", raw)
            }
            "cancel" -> {
                numberIdRegex.find(text)?.let { args.put("id", it.groupValues[1]) }
                if (!args.has("id")) {
                    extractAfterTrigger(text, listOf("取消提醒", "删掉提醒", "cancel reminder"))
                        ?.let { args.put("id", it) }
                }
            }
        }
    }

    private fun extractScheduled(
        text: String,
        action: String,
        parsed: ChineseTimeParser.Parsed?,
        now: LocalDateTime,
        args: JSONObject,
    ) {
        when (action) {
            "create" -> {
                parsed?.let {
                    if (it.relative) {
                        args.put("after", ChineseTimeParser.secondsUntil(it.dateTime, now))
                    } else {
                        args.put("at", it.iso())
                        args.put("hour", it.dateTime.hour)
                        args.put("minute", it.dateTime.minute)
                    }
                    args.put("repeat", it.repeat.name)
                }
                args.put("prompt", scheduledPrompt(text))
                args.put("label", scheduledPrompt(text).take(40))
            }
            "cancel" -> {
                numberIdRegex.find(text)?.let { args.put("id", it.groupValues[1]) }
                if (!args.has("id")) {
                    extractAfterTrigger(text, listOf("取消定时任务", "删掉定时任务", "cancel scheduled task"))
                        ?.let { args.put("id", it) }
                }
            }
        }
    }

    private fun extractDevice(text: String, action: String, args: JSONObject) {
        if (action == "info") Unit
        if (text.contains("内存") || text.contains("memory", ignoreCase = true)) {
            args.put("includeMemory", true)
        }
    }

    private fun extractMedia(text: String, action: String, args: JSONObject) {
        val lower = text.lowercase(Locale.ROOT)
        val resolved = when {
            lower.contains("继续播放") || lower.contains("恢复播放") || lower.contains("resume") -> "resume"
            lower.contains("停止播放") || lower.contains("stop") -> "stop"
            lower.contains("下一首") || lower.contains("next") -> "next"
            lower.contains("上一首") || lower.contains("previous") -> "previous"
            lower.contains("状态") || lower.contains("status") -> "status"
            lower.contains("列表") || lower.contains("list") -> "list"
            else -> action
        }
        args.put("action", resolved)
        if (resolved == "play") {
            // 语义（kind）在抽取层定好，执行层只消费，不在执行层 contain() 猜意图。
            val request = musicRequest(text)
            args.put("kind", request.kind)
            if (request.query.isNotBlank()) args.put("query", request.query)
            if (request.artist.isNotBlank()) args.put("artist", request.artist)
            if (request.chart.isNotBlank()) args.put("chart", request.chart)
        }
    }

    /**
     * 「音量调到百分之六十」→ set:60；「音量调大一点」→ adjust:+10。
     * 解析不出具体数值就不写 percent，必需槽缺失会让路由退回原链路 ——
     * 「调到 60 分」不是音量指令。
     *
     * @param action set = 绝对值；adjust = 相对增减；status = 只看不改。
     */
    private fun extractVolume(text: String, action: String, args: JSONObject) {
        if (text.contains("铃声") || text.contains("来电")) args.put("stream", "ring")
        if (action == "status") return
        val normalized = normalizeVolumePercent(text)
        when (action) {
            "set" -> {
                val absolute = absoluteVolumePercent(normalized) ?: when {
                    normalized.contains("最大") || normalized.contains("最响") -> 100
                    normalized.contains("最小") || normalized.contains("最轻") -> 0
                    else -> null
                }
                absolute?.let { args.put("percent", it.coerceIn(0, 100)) }
            }
            "adjust" -> volumeDeltaPercent(normalized)?.let { args.put("percent", it) }
        }
    }

    /**
     * 把中文百分比归一成 `N%`：`百分之六十` → `60%`，`调到六十` → `调到 60%`。
     * 数字后面跟「分 / 块 / 元…」时不做替换，那些是别的量词。
     */
    private fun normalizeVolumePercent(input: String): String {
        var out = input
        out = out.replace(Regex("百分百"), "100%")
        Regex("百分之([零一二两三四五六七八九十百]{1,4})")
            .findAll(out).toList().reversed().forEach { match ->
                val value = chineseNumber(match.groupValues[1]) ?: return@forEach
                out = out.replaceRange(match.range, "$value%")
            }
        Regex(
            "(调到|调至|调成|设成|设为|设置为|改成|改为|变成|变到)\\s*" +
                "([零一二两三四五六七八九十百]{1,4})(?!\\s*[分块元秒度个次])",
        ).findAll(out).toList().reversed().forEach { match ->
            val value = chineseNumber(match.groupValues[2]) ?: return@forEach
            out = out.replaceRange(match.range, "${match.groupValues[1]} $value%")
        }
        return out
    }

    /** 绝对值：只认百分比或句末的「调到 60」；「调到 60 分」不算。 */
    private fun absoluteVolumePercent(text: String): Int? {
        Regex(
            """(?:调到|调至|调成|设成|设为|设置为|改成|改为|变成|变到)\s*(\d{1,3})\s*(?:%|$|[,.!?;])""",
        ).find(text)?.groupValues?.get(1)?.toIntOrNull()?.let {
            return it.coerceIn(0, 100)
        }
        return Regex("""(?:音量|声音|响度|铃声)\s*(\d{1,3})\s*%""")
            .find(text)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(0, 100)
    }

    /** 相对增减：显式「调大 20%」优先，否则按方向词给步长。 */
    private fun volumeDeltaPercent(text: String): Int? {
        Regex("""(?:调大|增大|加大|增加|提高|升高|上调)\s*(\d{1,3})\s*%""")
            .find(text)?.groupValues?.get(1)?.toIntOrNull()?.let {
                return it.coerceIn(0, 100)
            }
        Regex("""(?:调小|减小|减少|降低|调低|下调)\s*(\d{1,3})\s*%""")
            .find(text)?.groupValues?.get(1)?.toIntOrNull()?.let {
                return -it.coerceIn(0, 100)
            }
        val up = listOf(
            "调大", "增大", "加大", "增加", "提高", "升高", "上调",
            "大一点", "大一些", "大点", "大很多", "大声", "高一点",
        ).any { text.contains(it) }
        val down = listOf(
            "调小", "减小", "减少", "降低", "调低", "下调",
            "小一点", "小一些", "小点", "小很多", "小声", "低一点",
        ).any { text.contains(it) }
        if (up == down) return null
        val step = when {
            text.contains("一点") || text.contains("一些") || text.contains("稍微") ||
                text.contains("点儿") || text.contains("点") -> 10
            text.contains("很多") || text.contains("大幅") || text.contains("大量") ||
                text.contains("一大") -> 40
            else -> 20
        }
        return if (up) step else -step
    }

    /**
     * 把一句点歌拆成语义请求，而不是做字符串手术。
     *
     * 只剥白名单触发词（播放/放/来/听/play…），实体一个字都不丢：
     * 「播放轻音乐」→ genre=轻音乐（不是歌名「轻」）；「play music」→ none（走榜单）。
     */
    private fun musicRequest(text: String): MusicRequest {
        var t = text.trim()
        val triggers = listOf(
            "播放一首", "播放一首儿", "播放一下", "播放", "放一首", "放一首儿", "放个", "放",
            "来一首", "来一首儿", "来首", "来点", "想听一首", "想听", "要听一首", "要听",
            "听一首", "听首", "听个", "听", "唱一首", "唱首", "唱",
            "play", "put on",
        )
        for (trigger in triggers.sortedByDescending { it.length }) {
            val index = t.indexOf(trigger, ignoreCase = true)
            if (index >= 0) {
                t = t.substring(index + trigger.length)
                break
            }
        }
        t = t.trim().trimStart(':', '：', ',', '，', '.', '。', '!', '！', '?', '？', ' ')
        // 「放首歌」→「」；「放首歌周杰伦」→「周杰伦」
        t = t.replace(Regex("""^一?[首歌]\s*"""), "").trim()
        t = t.replace(Regex("""^(?:给我|帮我|帮忙|麻烦)\s*"""), "").trim()
        t = t.trimEnd('了', '吧', '呀', '啊', '呢', '哦')

        // 泛指目标：没有点具体的东西 → 榜单，不拿 "music" 去搜歌名。
        if (MusicVocabulary.isGenericTarget(t)) {
            return MusicRequest(kind = "none")
        }

        // 榜单意图是白名单整词，不是模糊嗅探。
        MusicVocabulary.chartOf(t)?.let { chart ->
            return MusicRequest(kind = "chart", chart = chart)
        }

        // 流派/场景：整词保留，绝不剥尾部的「音乐」。
        MusicVocabulary.genreOf(t)?.let { genre ->
            return MusicRequest(kind = "genre", query = genre)
        }
        MusicVocabulary.sceneOf(t)?.let { scene ->
            return MusicRequest(kind = "scene", query = scene)
        }

        // 「周杰伦的稻香」→ song=稻香 + artist=周杰伦；「周杰伦的歌」→ artist。
        splitArtistSong(t)?.let { return it }

        // 普通歌名：尾缀剥离带守卫，剥坏了就保留原串。
        val original = t
        val candidate = t.replace(Regex("""(?:的)?(?:歌曲|音乐|曲子|歌)$"""), "").trim()
        if (candidate.endsWith('的') && candidate.length > 1) {
            return MusicRequest(kind = "artist", query = candidate.removeSuffix("的").trim())
        }
        val stripped = guardedStrip(original) {
            it.replace(Regex("""(?:的)?(?:歌曲|音乐|曲子|歌)$"""), "")
        }
        if (MusicVocabulary.isGenericTarget(stripped) || stripped.isBlank()) {
            return MusicRequest(kind = "none")
        }
        return MusicRequest(kind = "song", query = stripped.take(40))
    }

    /** 歌手/歌名拆分上提到抽取层：「周杰伦的稻香」→ artist=周杰伦, song=稻香。 */
    private fun splitArtistSong(t: String): MusicRequest? {
        val index = t.indexOf('的')
        if (index <= 0) return null
        val head = t.substring(0, index).trim()
        val tail = t.substring(index + 1).trim()
        if (head.length < 2 || tail.isEmpty()) return null
        if (tail in ARTIST_TAIL_WORDS) {
            return MusicRequest(kind = "artist", query = head)
        }
        val songPart = tail.replace(Regex("""(?:歌曲|音乐|曲子)$"""), "").trim()
        if (songPart.length < 2) return null
        return MusicRequest(kind = "song", query = songPart.take(40), artist = head)
    }

    /**
     * 剥离守卫：白名单剥离可以，启发式截断不行。
     * 剥完为空 / 只剩 1 个字 / 剩下的仍是流派词 → 认为破坏了实体，恢复原串。
     */
    private fun guardedStrip(original: String, transform: (String) -> String): String {
        val stripped = transform(original).trim()
        return when {
            stripped.isBlank() -> original.trim()
            stripped.length == 1 && original.trim().length > 1 -> original.trim()
            MusicVocabulary.isGenreOrScene(stripped) -> original.trim()
            else -> stripped
        }
    }

    private data class MusicRequest(
        val kind: String,
        val query: String = "",
        val artist: String = "",
        val chart: String = "",
    )

    private fun extractApp(text: String, action: String, args: JSONObject) {
        val rawApp = extractAfterTrigger(
            text,
            listOf("打开应用", "启动应用", "启动app", "打开app", "launch app", "打开", "启动", "launch"),
        )
        // 「打开app」→ 原文为空：保留原串而不是截空。
        val app = rawApp?.let { value ->
            guardedStrip(value) {
                it.replace(Regex("""^(?:应用|app)\s*""", RegexOption.IGNORE_CASE), "")
            }
        }
        if (!app.isNullOrBlank()) args.put("app", app)
        args.put("action", action)
    }

    /**
     * 写入正文：只认严格的短语模板，不做模糊截取。
     *
     * 「把 hello 写入 /a.txt」→ content=hello；
     * 「内容为 hello 写入 /a.txt」→ content=hello。
     * 抽不出来就留空，让路由退回原链路——**不猜**。
     */
    private fun extractWriteContent(text: String, args: JSONObject) {
        Regex(
            """(?:内容为|内容是|写入|写|save)\s+(.+?)\s+(?:到|to)\s+/""",
            RegexOption.IGNORE_CASE,
        )
            .find(text)?.let { args.put("content", it.groupValues[1].trim()) }
        if (!args.has("content")) {
            Regex("""(?:把|将)\s+(.+?)\s+(?:写入|写到|保存到|存到)""")
                .find(text)?.let { args.put("content", it.groupValues[1].trim()) }
        }
    }

    private fun reminderTitle(text: String): String {
        var t = ChineseTimeParser.stripTimeExpressions(text)
        listOf("记得提醒我", "提醒我", "提醒一下", "到点提醒", "记得提醒", "remind me to", "remind me", "reminder")
            .forEach { t = t.replace(it, " ", ignoreCase = true) }
        t = guardedStrip(t) { value ->
            value
                .replace(Regex("""^(?:要|去|一下|我)\s*"""), "")
                .replace(Regex("""^in\s+""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""^to\s+""", RegexOption.IGNORE_CASE), "")
        }
        return t.replace(Regex("""\s+"""), " ").trim()
            .trim('，', ',', '。', '.', ':', '：')
            .ifBlank { "提醒" }
            .take(80)
    }

    private fun scheduledPrompt(text: String): String {
        var t = ChineseTimeParser.stripTimeExpressions(text)
        listOf(
            "创建定时任务", "新建定时任务", "定时任务", "定时帮我", "定时", "每天帮我", "每日帮我",
            "每周帮我", "工作日帮我", "帮我", "替我", "请", "schedule task", "every day help me",
        ).forEach { t = t.replace(it, " ", ignoreCase = true) }
        return t.replace(Regex("""\s+"""), " ").trim().ifBlank { text.take(120) }
    }

    private fun extractAfterTrigger(text: String, triggers: List<String>): String? {
        for (trigger in triggers.sortedByDescending { it.length }) {
            val index = text.indexOf(trigger, ignoreCase = true)
            if (index >= 0) {
                val start = index + trigger.length
                val tail = text.substring(start)
                    .trim()
                    .trimStart(':', '：', ',', '，', '。', '.', '-', ' ')
                if (tail.isNotBlank()) return tail
            }
        }
        return null
    }

    private fun normalizeContent(input: String): String =
        Normalizer.normalize(input, Normalizer.Form.NFKC)
            .replace('　', ' ')
            .trim()

    private fun summaryFor(route: AssistantRoute, args: JSONObject): String {
        val target = args.optString("title").ifBlank {
            args.optString("label").ifBlank {
                args.optString("content").ifBlank {
                    args.optString("keywords").ifBlank {
                        args.optString("query").ifBlank {
                            args.optString("app").ifBlank {
                                args.optString("path").ifBlank { args.optString("percent") }
                            }
                        }
                    }
                }
            }
        }
        val whenText = args.optString("at").ifBlank {
            args.optString("after").ifBlank { args.optString("durationText") }
        }
        return listOf(route.label, target, whenText)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
            .take(160)
    }

    companion object {
        /** 「周杰伦的歌 / 歌曲 / 曲子 / 音乐」→ 歌手意图，不是歌名。 */
        private val ARTIST_TAIL_WORDS = setOf("歌", "歌曲", "曲子", "音乐")

        private val CHINESE_DIGITS = mapOf(
            '零' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
            '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9,
        )

        /** 中文数字 → 整数，覆盖 0~100（五、十五、五十、五十五、一百）。 */
        private fun chineseNumber(src: String): Int? {
            if (src.isEmpty()) return null
            if (src == "一百" || src == "百") return 100
            if (src.length == 1) return CHINESE_DIGITS[src[0]]
            val ten = src.indexOf('十')
            if (ten >= 0) {
                val tens = if (ten == 0) 1 else (CHINESE_DIGITS[src[0]] ?: return null)
                val ones = if (ten == src.length - 1) {
                    0
                } else {
                    CHINESE_DIGITS[src[ten + 1]] ?: return null
                }
                return tens * 10 + ones
            }
            return null
        }
    }
}
