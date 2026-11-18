package com.pilot.agent.assistant.routing

import java.text.Normalizer
import java.util.Locale

data class RouteMatch(
    val route: AssistantRoute,
    val score: Int,
    val matchedKeywords: List<String>,
    val matchedPatterns: List<String>,
) {
    val longestKeywordLength: Int
        get() = matchedKeywords.maxOfOrNull { it.length } ?: 0

    val isStrong: Boolean
        get() = matchedPatterns.isNotEmpty() || matchedKeywords.any {
            val ascii = it.all { ch -> ch.code < 128 }
            if (ascii) it.length >= 3 else it.length >= 2
        }
}

/**
 * 纯函数打分器：不是「第一条命中就赢」。
 *
 * 所有启用的路由都会被打分，然后按分数 → 优先级 → 最长关键词 → id 排序。
 */
object AssistantRouteMatcher {

    const val MIN_SCORE = 50

    fun rank(
        userText: String,
        routes: List<AssistantRoute>,
        limit: Int = 3,
    ): List<RouteMatch> {
        val text = normalizeText(userText)
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<RouteMatch>()
        for (route in routes) {
            if (!route.enabled) continue
            if (route.capability.isBlank() || route.action.isBlank()) continue
            if (!CapabilityRegistry.supports(route.capability, route.action)) continue

            val excluded = route.exclude.any { term ->
                term.isNotBlank() && containsTerm(text, normalizeText(term))
            }
            if (excluded) continue

            if (route.allKeywords.any { !containsTerm(text, normalizeText(it)) }) continue

            val matchedKeywords = route.keywords
                .filter { it.isNotBlank() }
                .map { it.trim() }
                .filter { containsTerm(text, normalizeText(it)) }
            val matchedPatterns = route.patterns.filter { pattern ->
                runCatching { Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(text) }
                    .getOrDefault(false)
            }
            if (matchedKeywords.isEmpty() && matchedPatterns.isEmpty()) continue

            var score = 40
            matchedKeywords.forEach { keyword ->
                score += 8 + minOf(normalizeText(keyword).length, 12)
            }
            score += matchedPatterns.size * 18
            score += route.allKeywords.count { containsTerm(text, normalizeText(it)) } * 6
            out += RouteMatch(route, score, matchedKeywords, matchedPatterns)
        }
        return out.sortedWith(
            compareByDescending<RouteMatch> { it.score }
                .thenByDescending { it.route.priority }
                .thenByDescending { it.longestKeywordLength }
                .thenBy { it.route.id },
        ).take(limit.coerceAtLeast(0))
    }

    fun normalizeText(input: String): String {
        return normalizeTextPreservingCase(input).lowercase(Locale.ROOT)
    }

    /**
     * 匹配不区分大小写，但抽出来的**参数值**必须保留原样（路径、命令、URL、
     * 记忆正文、人名都是值，不是意图）。这个变体做同样的兼容性与空白归一化，
     * 但不折叠大小写。
     */
    fun normalizeTextPreservingCase(input: String): String {
        val nfkc = Normalizer.normalize(input, Normalizer.Form.NFKC)
        return nfkc
            .replace('　', ' ')
            // 键盘与模型常见标点差异：只归一化标点，不删语义字符。
            .replace('，', ',')
            .replace('。', '.')
            .replace('；', ';')
            .replace('：', ':')
            .replace('！', '!')
            .replace('？', '?')
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun containsTerm(normalizedText: String, normalizedTerm: String): Boolean {
        if (normalizedTerm.isEmpty()) return false
        val ascii = normalizedTerm.all { it.code < 128 }
        if (!ascii) return normalizedText.contains(normalizedTerm)
        val escaped = Regex.escape(normalizedTerm)
        // `_` 也算词内字符：`hub_call` 里的 "call" 不是「打电话」。
        return Regex("""(?<![a-z0-9_])$escaped(?![a-z0-9_])""").containsMatchIn(normalizedText)
    }
}
