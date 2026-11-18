package com.pilot.agent.assistant.llm

import com.pilot.agent.assistant.exec.CapabilitySafety
import org.json.JSONObject

/**
 * 端侧模型的**槽位补值**。
 *
 * ## 分工（Minis 的实测结论）
 *
 * 0.3B 不会「从列表里选工具」，但会「从一句话里找值」。所以路由层负责选能力，
 * 这个类只负责给自由文本槽位补值 —— 任务简单一个量级，小模型够用。
 *
 * ## 防幻觉
 *
 * 模型补出来的每个值都要能追溯到用户原话：
 * - 路径 / 网址 / 命令：逐词校验（[CapabilitySafety.isVerbatim]）；
 * - 其余（正文、查询词、应用名）：连续子串校验（[CapabilitySafety.isContiguousFrom]）。
 *
 * 校验不过的值直接丢掉；一个有效值都没有就返回 null，调用方回落原链路。
 *
 * ## 为什么是「单条例句 + 单行」
 *
 * P40 / Qwen3-0.6B 实测（2026-11-18）：
 * - 多轮「用户：… / 输出：…」排列 → 模型直接输出空串，或把「现在：」抄进值里；
 * - system 回合 + 用户回合两段式 → 时好时坏，/no_think 还常被当正文吞掉；
 * - **一条内联「例句：X → JSON」+「现在：<原话>」** → 稳定、首字约 0.4s；
 * - 两条例句会把模型带偏（抄错模板），所以每次只给一条，且要跟用户句式同构。
 *
 * `/no_think` 独占一行放在 `<|im_end|>` 前 —— 紧贴正文时会被分词器并成
 * 「/no_t」之类的普通文本，模型随即开始长篇思考。
 *
 * ## 为什么要多次尝试
 *
 * 0.6B 采样不稳定：同一个 prompt 可能一次答对、一次截断（「明天开会」→「明天会」）、
 * 一次编造（→「明天会议」）。所以每次生成都过一遍校验，最多换 3 次
 * （换例句 / 原 prompt 重采）。3 次都不过就认输回落 —— 宁可慢 ~1 秒，也不执行错值。
 */
class SlotFiller(
    private val available: () -> Boolean,
    private val generate: (String) -> String?,
) {

    /**
     * @param missing 需要模型补的槽位名
     * @return 合并后的参数；null = 模型不可用 / 没抽出有效值
     */
    fun fill(
        userText: String,
        capability: String,
        args: JSONObject,
        missing: Set<String>,
        noThink: Boolean = false,
    ): JSONObject? {
        if (missing.isEmpty()) return args
        if (!available()) return null

        val slots = missing.toList()
        for (prompt in attempts(userText, capability, slots, noThink)) {
            val raw = generate(prompt) ?: continue
            val parsed = parse(raw, slots, userText) ?: continue
            val merged = JSONObject(args.toString())
            val keys = parsed.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                merged.put(key, parsed.optString(key))
            }
            return merged
        }
        return null
    }

    /**
     * 尝试序列：首选例句 → 备用例句 → 首选重采。
     *
     * 采样的失败（空串 / 截断 / 编造）是随机的，同一 prompt 重跑一次通常就好；
     * 但如果模型一直在抄错模板，就得换一条同构的例句。
     */
    private fun attempts(
        userText: String,
        capability: String,
        slots: List<String>,
        noThink: Boolean,
    ): List<String> {
        val demos = demosFor(capability, userText)
        if (demos.isEmpty()) {
            val bare = buildPrompt(userText, capability, slots, noThink, demo = null)
            return listOf(bare, bare)
        }
        val prompts = demos.map { buildPrompt(userText, capability, slots, noThink, it) }
        return (prompts + prompts.first()).take(MAX_ATTEMPTS)
    }

    /** 与用户句式最匹配的例句排在前面。 */
    private fun demosFor(capability: String, userText: String): List<Demo> =
        DEMOS[capability].orEmpty().sortedByDescending { demo ->
            demo.hints.count { userText.contains(it) }
        }

    /**
     * 极简 prompt：一句指令 + 一条例句 + 原话。
     *
     * 有该能力的例句就用真实例句；没有就只给原话（宁可少一个 shot，
     * 也不硬造占位符 —— 0.3B 会把占位符原样抄进输出）。
     */
    fun buildPrompt(
        userText: String,
        capability: String,
        slots: List<String>,
        noThink: Boolean = false,
    ): String = buildPrompt(
        userText,
        capability,
        slots,
        noThink,
        demosFor(capability, userText).firstOrNull(),
    )

    private fun buildPrompt(
        userText: String,
        capability: String,
        slots: List<String>,
        noThink: Boolean,
        demo: Demo?,
    ): String {
        val sb = StringBuilder()
        sb.append("<|im_start|>user\n")
        sb.append(instructionFor(capability, slots))
        if (demo != null) {
            sb.append("例句：").append(demo.user).append(" → ").append(demo.output).append('\n')
        }
        sb.append("现在：").append(userText.take(60))
        // ★ /no_think 必须放在**用户回合内**（<|im_end|> 之前），且**独占一行**。
        //   紧贴正文时会被分词器并成普通文本，模型随即长篇思考
        //   （实测吐出「/no_t」并把思考过程写进回复）。
        if (noThink) sb.append("\n/no_think")
        sb.append("<|im_end|>\n<|im_start|>assistant\n")
        return sb.toString()
    }

    /** 少数能力需要一句额外提示，否则 0.6B 会把触发词当成值抄出来。 */
    private fun instructionFor(capability: String, slots: List<String>): String = when (capability) {
        "reminder" ->
            "提取参数 ${slots.joinToString(",")}（要提醒的事，不要「提醒我」），只输出 JSON。"
        else -> "提取参数 ${slots.joinToString(",")}，只输出 JSON。"
    }

    /**
     * 解析并校验模型输出。
     *
     * 只接受问过的参数名；模型自己加的键、原话里找不到的值，一律丢掉。
     */
    fun parse(raw: String, slots: List<String>, userText: String): JSONObject? {
        val text = raw.trim()
        val start = text.indexOf('{')
        if (start < 0) return null
        val end = text.lastIndexOf('}')
        if (end <= start) return null
        val obj = runCatching { JSONObject(text.substring(start, end + 1)) }.getOrNull() ?: return null

        val out = JSONObject()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key !in slots) continue
            val value = obj.optString(key).trim()
            if (value.isBlank()) continue
            // 模型抄示例 / 抄占位符 / 截断的确定性证据：值不是用户原话的连续片段。
            val valid = if (key in VERBATIM_SLOTS) {
                CapabilitySafety.isVerbatim(value, userText)
            } else {
                CapabilitySafety.isContiguousFrom(value, userText)
            }
            if (!valid) continue
            out.put(key, value)
        }
        return if (out.length() == 0) null else out
    }

    /** 一条「示例用户话 → 应输出 JSON」+ 触发词，用来挑和用户句式最匹配的那条。 */
    private data class Demo(val user: String, val output: String, val hints: List<String>)

    private companion object {
        /** 这些槽位的值必须原样来自用户原话。 */
        val VERBATIM_SLOTS = setOf("path", "url", "command")

        /** 最多生成几次。每次约 0.5~1.2 秒，再多就不如直接回落云端了。 */
        const val MAX_ATTEMPTS = 3

        /**
         * 例句必须是**另一句真实用户话 + 从这句话抽出的真实值**。
         * 用占位符（「示例值」）时 0.3B 会原样抄进输出。
         *
         * 同一能力给了两条句式不同的例句（比如「把 X 写入 Y」和「保存到 Y 内容 X」），
         * 运行时按 hints 命中挑最贴近用户原话的那条 —— 句式不同构时模型会把路径抄成正文。
         */
        val DEMOS: Map<String, List<Demo>> = mapOf(
            "file_write" to listOf(
                Demo("把 小结 写入 /tmp/a.txt", """{"content":"小结"}""", listOf("把", "写入", "写到")),
                Demo("保存到 /tmp/c.txt 内容 小结", """{"content":"小结"}""", listOf("保存到", "内容", "存到")),
            ),
            "media" to listOf(
                Demo("播放 晴天", """{"kind":"song","query":"晴天"}""", listOf("播放", "听")),
            ),
            "reminder" to listOf(
                Demo("提醒我 喝水", """{"title":"喝水"}""", listOf("提醒")),
            ),
        )
    }
}
