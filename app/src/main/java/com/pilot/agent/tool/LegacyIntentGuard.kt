package com.pilot.agent.tool

/**
 * 遗留匹配器的「这到底是不是一条指令」判定。
 *
 * ## 为什么需要它
 *
 * 遗留匹配器里有两个**宽松**匹配器（只看关键词在不在句子里）：
 * - `matchScreenshot`：「截图 / 截屏」
 * - `matchDevice`：「电量 / 存储空间 / 蓝牙状态 …」
 *
 * 于是讨论句会真的动手：
 * - 「截图功能怎么实现」→ 截了一张图；
 * - 「我们可以加个电量显示功能」→ 去查了电量。
 *
 * 助理快路径有自己的讨论/疑问闸（`AssistantRouter`），但快路径**拒绝即回落** ——
 * 拒绝之后还是落到这两个匹配器手里，所以闸得加在遗留这一侧。
 *
 * ## 两档词表
 *
 * - [DISCUSSION_CUES]：讨论、需求、方案类。出现这些词 = 用户在**聊**这个能力。
 *   对任何能力都不该动手（连只读查询也不该）。
 * - [QUESTION_CUES]：怎么 / 如何 / 怎样 / 为什么。出现这些词 = 用户在**问**。
 *   只读查询照常回答（「网络怎么样」就是问网络），但**动手**类的要挡
 *   （「怎么截图」不是在要截图）。
 *
 * 单独拆出来是因为遗留设备匹配器自己的关键词里就有「网络怎么样」「定位开了吗」
 * 这类问句 —— 一刀切问句会把正常查询也挡掉。
 */
object LegacyIntentGuard {

    /** 讨论 / 需求 / 方案类词：出现即认为用户在聊，不是在调用。 */
    val DISCUSSION_CUES = listOf(
        "讨论",
        "方案",
        "实现",
        "原理",
        "功能",
        "开发",
        "设计",
        "接口",
        "规则",
        "代码",
        "优化",
        "排查",
        "日志",
        "支持",
        "加个",
        "添加",
        "增加",
        "自定义",
    )

    /** 疑问词：用户在问，不是在要。 */
    val QUESTION_CUES = listOf(
        "怎么",
        "如何",
        "怎样",
        "为什么",
        "咋",
    )

    /** 讨论 / 需求句：任何匹配器都不该动手。 */
    fun isDiscussion(text: String): Boolean =
        DISCUSSION_CUES.any { text.contains(it) }

    /** 疑问句：只读能力可以照常回答，动手的能力要挡。 */
    fun isQuestion(text: String): Boolean =
        QUESTION_CUES.any { text.contains(it) }

    /** 疑问句 + 动手的动作 —— 绝不能执行。 */
    fun isQuestionOrDiscussion(text: String): Boolean =
        isDiscussion(text) || isQuestion(text)

    /**
     * 抽出来的"路径"得像路径。
     *
     * 遗留的写文件正则 `把(.+?)写入(.+)` 会把「讨论一下把日志写入文件的方案」里的
     * 「的方案」当成路径。判据：带路径分隔符，或带扩展名（`.txt` / `.md`）。
     * 相对文件名（`a.txt`）过；纯中文短语（`的方案`）不过。
     */
    fun looksLikePath(value: String): Boolean {
        val v = value.trim().trim('"', '\'', '“', '”')
        if (v.isEmpty()) return false
        if (v.contains('/') || v.contains('\\')) return true
        val dot = v.lastIndexOf('.')
        return dot > 0 && dot < v.length - 1 && v.length - dot <= 8
    }
}
