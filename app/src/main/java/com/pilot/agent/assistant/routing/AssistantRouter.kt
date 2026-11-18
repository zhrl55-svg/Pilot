package com.pilot.agent.assistant.routing

import org.json.JSONObject

sealed class RouteDecision {
    data class Local(
        val route: AssistantRoute,
        val capability: String,
        val action: String,
        val args: JSONObject,
        val needsConfirmation: Boolean,
        val needsModelExtraction: Boolean,
        val missingSlots: Set<String>,
        val score: Int,
        val ranked: List<RouteMatch> = emptyList(),
        val summary: String = "",
        val ambiguousTime: Boolean = false,
    ) : RouteDecision()

    data class Cloud(
        val reason: String,
        val note: String? = null,
        val ranked: List<RouteMatch> = emptyList(),
    ) : RouteDecision()
}

/**
 * 从「一句话」到「本地调用」或「交给云端」的**唯一入口**。
 *
 * 这个类不起协程、不碰模型：自由文本补值是显式的下一步，
 * 所以确定性的这条路永远不等模型加载。
 *
 * 移植自 Minis 的 `AssistantRouter`，按 Pilot 的 v1 能力调整。
 */
class AssistantRouter(
    private val extractor: SlotExtractor = SlotExtractor(),
) {
    fun route(
        userText: String,
        routes: List<AssistantRoute>,
    ): RouteDecision {
        // 设备间交接/回执的信封不是用户说的话，整段跳过端侧路由。
        if (isBridgeEnvelope(userText)) {
            return RouteDecision.Cloud("internal-message")
        }
        val ranked = AssistantRouteMatcher.rank(userText, routes, limit = 3)
        val best = ranked.firstOrNull()
            ?: return RouteDecision.Cloud("no-match")
        if (best.score < AssistantRouteMatcher.MIN_SCORE) {
            return RouteDecision.Cloud("low-confidence", ranked = ranked)
        }

        val capability = CapabilityRegistry.get(best.route.capability)
            ?: return RouteDecision.Cloud("unknown-capability", ranked = ranked)
        val action = capability.action(best.route.action)
            ?: return RouteDecision.Cloud("unknown-action", ranked = ranked)

        // 媒体指令只接受短句 / 语音转写。一段长文本里提到「暂停音乐」不等于
        // 用户要控制播放器 —— 那是讨论、引用或汇报。超长或带换行时交给云端
        // 按整段语境判断，避免把一封长消息执行成一次点歌/暂停。
        if (capability.id == "media" &&
            (userText.contains('\n') ||
                AssistantRouteMatcher.normalizeText(userText).length > MEDIA_COMMAND_MAX_LENGTH)
        ) {
            return RouteDecision.Cloud("long-text", ranked = ranked)
        }

        val extraction = extractor.extract(userText, best.route)

        // 写文件必须有明确的 path 槽。普通句子里出现「写入」二字
        // （例如汇报里说「写入经 ConfigBridge 确认卡」）不是写文件指令；
        // 抽不出 path 就交回原链路 —— 不弹确认卡，也绝不会落到某个默认路径上。
        if (capability.id in PATH_REQUIRED_WRITE_CAPABILITIES &&
            extraction.args.optString("path").isBlank()
        ) {
            return RouteDecision.Cloud("no-args", note = "no-args", ranked = ranked)
        }
        // 提醒与定时任务必须有时间点，否则「提醒我」没有任何可执行信息。
        if (capability.id == "reminder" && action.name == "schedule" &&
            extraction.args.optString("at").isBlank() &&
            extraction.args.optLong("after", 0L) <= 0L
        ) {
            return RouteDecision.Cloud("no-args", note = "no-args", ranked = ranked)
        }
        if (capability.id == "scheduled_task" && action.name == "create" &&
            extraction.args.optString("at").isBlank() &&
            extraction.args.optLong("after", 0L) <= 0L
        ) {
            return RouteDecision.Cloud("no-args", note = "no-args", ranked = ranked)
        }

        val readOnly = action.readOnly
        val highRisk = action.risk == CapabilityRisk.HIGH

        // 疑问句不是指令。
        //
        // 「怎么打开应用」里含关键词「打开应用」，本来会被当成打开 App 的指令 ——
        // 打开一个叫「应用」的东西。用户在**问**怎么打开，不是在**要**你打开。
        //
        // 只挡动作型能力（打开、调音量、播放控制、建提醒…）；查询型
        // （查电量、看播放状态、搜记忆）照常接管 —— 回答状态本身没问题。
        if (!readOnly) {
            val normalized = AssistantRouteMatcher.normalizeText(userText)
            if (QUESTION_CUES.any { normalized.contains(it) }) {
                return RouteDecision.Cloud("question-not-command", ranked = ranked)
            }
        }
        // 只有破坏性操作才弹确认卡。
        //
        // 之前 Minis 的口径是「任何写操作都要确认」，结果「暂停播放」「设个闹钟」
        // 「记个笔记」这类一句话就能撤销的操作全都要先点一次确认 —— 助手
        // 变得又慢又啰嗦，比它做的事还费事。
        //
        // 现在按破坏性分级：CapabilityRisk.HIGH（删除、取消、覆盖文件）
        // 仍然确认；其余（创建提醒、定时任务、播放控制）直接执行。
        val needsConfirmation = when (best.route.confirm) {
            ConfirmMode.ALWAYS -> true
            ConfirmMode.NEVER -> false
            ConfirmMode.WRITE -> highRisk
        }

        // 「句子里出现『电量』两个字」不等于「用户要查电量」。
        // 只命中关键词、没有命中任何模式时，只有关键词在句子里占足够大的
        // 比重才算指令；长句里顺带提一个短词退回原链路。
        //
        // 带自由文本槽的能力不适用长度/占比判断：槽值就是用户内容
        // （「找笔记 Project Alpha」里关键词只占一小段，但这是在搜索）。
        val hasExtractedSlot = action.requiredSlots.any {
            extraction.args.optString(it).isNotBlank()
        }
        if (readOnly && !hasExtractedSlot && best.matchedPatterns.isEmpty()) {
            val normalized = AssistantRouteMatcher.normalizeText(userText)
            val coverage = best.longestKeywordLength.toFloat() /
                normalized.length.coerceAtLeast(1)
            val topicMention = normalized.length > TOPIC_MENTION_MIN_LENGTH &&
                coverage < TOPIC_MENTION_MIN_COVERAGE
            val discussingStatusCapability =
                capability.id in STATUS_CAPABILITIES &&
                    normalized.length > TOPIC_DISCUSSION_MIN_LENGTH &&
                    DISCUSSION_CUES.any { normalized.contains(it) }
            if (topicMention || discussingStatusCapability) {
                return RouteDecision.Cloud("topic-mention", ranked = ranked)
            }
        }

        // 只靠一两个弱关键词构成的写路由太容易被误触发，交给更强的链路处理。
        if (!readOnly && !best.isStrong) {
            return RouteDecision.Cloud("weak-write-match", ranked = ranked)
        }
        if (extraction.ambiguous && !readOnly) {
            // 含糊的时间以前会弹确认卡让用户纠正。现在只有破坏性操作才问：
            // 「记笔记：明天买牛奶」里的「明天」在正文里，不是时间，
            // 为它弹卡纯属摩擦。解析出的时间会写在回执里，看得见就能改。
            //
            // 但不能因此关掉模型补值：「保存到 /x.txt 内容 明天开会」里的
            // 「明天」只是正文的一部分，content 照样要交给端侧模型补。
            // 否则 plan 会以 no-executor 直接回落（P5 验收抓到）。
            return RouteDecision.Local(
                route = best.route,
                capability = capability.id,
                action = action.name,
                args = extraction.args,
                needsConfirmation = needsConfirmation,
                needsModelExtraction = capability.needsLlm && extraction.missing.isNotEmpty(),
                missingSlots = extraction.missing,
                score = best.score,
                ranked = ranked,
                summary = extraction.summary,
                ambiguousTime = true,
            )
        }

        if (extraction.missing.isNotEmpty()) {
            if (capability.needsLlm) {
                return RouteDecision.Local(
                    route = best.route,
                    capability = capability.id,
                    action = action.name,
                    args = extraction.args,
                    needsConfirmation = needsConfirmation,
                    needsModelExtraction = true,
                    missingSlots = extraction.missing,
                    score = best.score,
                    ranked = ranked,
                    summary = extraction.summary,
                    ambiguousTime = extraction.ambiguous,
                )
            }
            return RouteDecision.Cloud("no-args", note = "no-args", ranked = ranked)
        }

        return RouteDecision.Local(
            route = best.route,
            capability = capability.id,
            action = action.name,
            args = extraction.args,
            needsConfirmation = needsConfirmation,
            needsModelExtraction = false,
            missingSlots = emptySet(),
            score = best.score,
            ranked = ranked,
            summary = extraction.summary,
            ambiguousTime = extraction.ambiguous,
        )
    }

    private companion object {
        /**
         * 这些能力会写文件，path 槽抽不出来就不该本地执行。
         */
        val PATH_REQUIRED_WRITE_CAPABILITIES = setOf("file_write")

        /**
         * 设备间交接消息的正文被 `[FROM: X] … [END X]` 包住。
         * 那是内部流量，永远不是用户指令。
         */
        fun isBridgeEnvelope(text: String): Boolean {
            val head = text.trimStart()
            if (!head.startsWith("[")) return false
            return head.startsWith("[FROM:", ignoreCase = true) ||
                head.startsWith("[FROM：", ignoreCase = true) ||
                head.startsWith("[END ", ignoreCase = true)
        }

        /** 超过这个长度还只命中一个短关键词，就当成「提到」而非「命令」。 */
        const val TOPIC_MENTION_MIN_LENGTH = 14

        /**
         * 疑问词：出现在**动作型**能力里就是「在问」，不是在「要你做」。
         *
         * 故意不含「什么」——「你还记得我喜欢喝什么吗」是查记忆，问句也该接管；
         * 「什么」太常见，挡掉会误伤查询。
         */
        val QUESTION_CUES = listOf("怎么", "如何", "怎样", "为什么", "咋")

        /** 媒体路由的最大指令长度，超出走云端。 */
        const val MEDIA_COMMAND_MAX_LENGTH = 20

        /** 关键词至少要占句子的这个比例，纯关键词匹配才可信。 */
        const val TOPIC_MENTION_MIN_COVERAGE = 0.30f

        /**
         * 状态/控制类能力没有自由文本槽：句子里出现「接口 / 方案 / 规则」
         * 这类讨论词汇时，用户是在聊这个能力，不是在调用它。
         */
        val STATUS_CAPABILITIES = setOf("device", "media", "volume")

        const val TOPIC_DISCUSSION_MIN_LENGTH = 8

        val DISCUSSION_CUES = listOf(
            "讨论", "方案", "接口", "规则", "触发", "代码", "实现",
            "优化", "排查", "日志", "设计", "开发", "对接",
        )
    }
}
