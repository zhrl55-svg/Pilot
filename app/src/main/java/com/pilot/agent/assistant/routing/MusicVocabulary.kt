package com.pilot.agent.assistant.routing

import java.util.Locale

/**
 * [T-music-slot-semantics] 音乐请求的语义词表。
 *
 * 这些词是**实体本身**，不是可丢弃的尾缀：「轻音乐」是一个流派，
 * 把尾部「音乐」剥掉只剩「轻」，一句点歌就降级成了歌名《轻》。
 * 抽取层先用这张表判定 kind，再决定要不要剥词；执行层只消费 kind，
 * 不再 contain() 关键词猜意图。
 */
object MusicVocabulary {

    /** 流派：命中后整词保留，检索走 type=1000（歌单）。 */
    val GENRES: List<String> = listOf(
        "轻音乐", "纯音乐", "古典音乐", "新世纪音乐", "佛教音乐", "白噪音",
        "钢琴曲", "吉他曲", "小提琴", "古筝", "二胡",
        "古典", "摇滚", "爵士", "电子", "民谣", "乡村", "说唱", "嘻哈",
        "流行", "新世纪", "佛音", "钢琴", "古风", "二次元", "金属", "蓝调",
    )

    /** 场景：同样是歌单检索目标（助眠、开车、学习……）。 */
    val SCENES: List<String> = listOf(
        "治愈", "助眠", "睡眠", "安眠", "催眠", "冥想", "放松", "减压", "解压",
        "疗愈", "学习", "工作", "专注", "阅读", "开车", "运动", "跑步",
        "健身", "瑜伽", "咖啡", "睡前", "清晨", "深夜", "雨天", "旅行",
    )

    /**
     * 榜单/固定列表意图。值就是执行层的 chart 参数；按词长降序匹配，
     * 避免「新歌榜」被「新歌」截胡。
     */
    private val CHART_WORDS: List<Pair<String, String>> = listOf(
        "每日推荐" to "daily",
        "最近播放" to "recent",
        "我喜欢的音乐" to "liked",
        "我喜欢" to "liked",
        "热歌榜" to "hot", "热门榜" to "hot", "排行榜" to "hot",
        "新歌榜" to "new",
        "飙升榜" to "rising",
        "热门" to "hot", "热歌" to "hot", "新歌" to "new", "飙升" to "rising",
        "最近" to "recent", "推荐" to "daily", "收藏" to "liked",
    ).sortedByDescending { it.first.length }

    /** 泛指目标：说明「随便放点」，不该再拿去搜歌名。 */
    private val GENERIC_TARGETS: Set<String> = setOf(
        "", "歌", "歌曲", "音乐", "曲子", "首歌", "一首歌", "歌儿", "点歌", "点音乐", "歌单",
        "music", "song", "songs", "some music", "some songs", "a song", "the music",
        "any music", "some song", "any song",
    )

    /** 命中的流派整词（最长优先）；没命中返回 null。 */
    fun genreOf(text: String): String? = longestMatch(text, GENRES)

    /** 命中的场景整词（最长优先）；没命中返回 null。 */
    fun sceneOf(text: String): String? = longestMatch(text, SCENES)

    /** 命中的榜单参数（hot/new/rising/recent/daily/liked）；没命中返回 null。 */
    fun chartOf(text: String): String? {
        val lower = text.lowercase(Locale.ROOT)
        return CHART_WORDS.firstOrNull { lower.contains(it.first.lowercase(Locale.ROOT)) }?.second
    }

    /** [word] 是否本身就是流派/场景词（用于尾缀剥离守卫）。 */
    fun isGenreOrScene(word: String): Boolean {
        val w = word.trim()
        return GENRES.any { it.equals(w, ignoreCase = true) } ||
            SCENES.any { it.equals(w, ignoreCase = true) }
    }

    /** 去掉触发词后只剩「歌 / 音乐 / music」，表示没有具体目标。 */
    fun isGenericTarget(text: String): Boolean {
        val normalized = text.trim()
            .trim('。', '.', ',', '，', '!', '！', '?', '？', ' ')
            .lowercase(Locale.ROOT)
            .replace(Regex("""\s+"""), " ")
        return normalized in GENERIC_TARGETS
    }

    private fun longestMatch(text: String, words: List<String>): String? {
        val lower = text.lowercase(Locale.ROOT)
        return words
            .filter { lower.contains(it.lowercase(Locale.ROOT)) }
            .maxByOrNull { it.length }
    }
}
