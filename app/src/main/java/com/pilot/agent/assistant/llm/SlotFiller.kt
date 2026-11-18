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
 * - 其余（正文、查询词、应用名）：子序列校验（[CapabilitySafety.isDerivedFrom]）。
 *
 * 校验不过的值直接丢掉；一个有效值都没有就返回 null，调用方回落原链路。
 *
 * ## 为什么这么窄
 *
 * 麒麟 990 上系统提示词每多 100 字就多约 0.6 秒首字延迟（Minis 实测）。
 * 所以 prompt 只给：一句指令、要抽的参数名、一两条真实示例、用户原话。
 * 示例里**绝不能用占位符** —— 0.3B 会把「示例值」原样抄进输出。
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
    ): JSONObject? {
        if (missing.isEmpty()) return args
        if (!available()) return null

        val slots = missing.toList()
        val prompt = buildPrompt(userText, capability, slots)
        var raw = generate(prompt) ?: return null
        var parsed = parse(raw, slots, userText)
        if (parsed == null) {
            // 0.3B 偶尔把 JSON 吐坏；同一 prompt 重试一次通常就好了。
            raw = generate(prompt) ?: return null
            parsed = parse(raw, slots, userText)
        }
        parsed ?: return null

        val merged = JSONObject(args.toString())
        val keys = parsed.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            merged.put(key, parsed.optString(key))
        }
        return merged
    }

    /**
     * 极简 prompt：指令 + 参数名 + 示例 + 原话。
     *
     * 有该能力的示例就用真实示例；没有就只给原话（宁可少一个 shot，
     * 也不硬造占位符）。
     */
    fun buildPrompt(userText: String, capability: String, slots: List<String>): String {
        val sb = StringBuilder()
        sb.append("<|im_start|>system\n")
        sb.append("从用户的话里提取参数值，只输出一个 JSON 对象，不要解释。")
        sb.append("值必须原样抄自用户的话，不要编造。<|im_end|>\n")
        sb.append("<|im_start|>user\n")
        sb.append("参数：").append(slots.joinToString(",")).append('\n')
        val demos = DEMOS[capability].orEmpty()
        if (demos.isNotEmpty()) {
            sb.append("示例：\n")
            demos.forEach { (user, output) ->
                sb.append("用户：").append(user).append('\n')
                sb.append("输出：").append(output).append('\n')
            }
        }
        sb.append("用户：").append(userText.take(60)).append('\n')
        sb.append("输出：")
        sb.append("<|im_end|>\n<|im_start|>assistant\n")
        return sb.toString()
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
            // 模型抄示例 / 抄占位符的确定性证据：值不在用户原话里。
            val valid = if (key in VERBATIM_SLOTS) {
                CapabilitySafety.isVerbatim(value, userText)
            } else {
                CapabilitySafety.isDerivedFrom(value, userText)
            }
            if (!valid) continue
            out.put(key, value)
        }
        return if (out.length() == 0) null else out
    }

    private companion object {
        /** 这些槽位的值必须原样来自用户原话。 */
        val VERBATIM_SLOTS = setOf("path", "url", "command")

        /**
         * 示例必须是**另一句真实用户话 + 从这句话抽出的真实值**。
         * 用占位符（「示例值」）时 0.3B 会原样抄进输出。
         */
        val DEMOS: Map<String, List<Pair<String, String>>> = mapOf(
            "file_write" to listOf(
                "把 hello 写入 /tmp/a.txt" to """{"content":"hello"}""",
                "把这段小结 写入 /tmp/b.txt" to """{"content":"这段小结"}""",
            ),
            "media" to listOf(
                "播放晴天" to """{"kind":"song","query":"晴天"}""",
                "来点轻音乐" to """{"kind":"genre","query":"轻音乐"}""",
            ),
            "reminder" to listOf(
                "提醒我喝水" to """{"title":"喝水"}""",
            ),
        )
    }
}
