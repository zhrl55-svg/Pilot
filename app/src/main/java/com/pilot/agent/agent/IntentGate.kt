package com.pilot.agent.agent

/**
 * 意图门：判断一句话是「操作指令」还是「闲聊/提问」。
 *
 * ## 为什么必须有
 * 语音输入不区分意图就送进 agent loop，后果很严重：用户说「你是谁呀」，
 * agent 会当成任务去点屏幕上的第一个可点元素。实测真机上一句话触发了 3 次乱点。
 *
 * 所以凡是没过这道门的句子，一律不执行任何设备操作。
 *
 * ## 判据
 * 不看问号看动词。「今天几号」是提问但不该乱点；「帮我看看几点了」有动词但也不该乱点。
 * 所以先认**操作动词**，再排除纯疑问句：
 * - 有操作动词 + 不是纯问句 → 任务
 * - 没有操作动词 → 交流（不执行）
 */
object IntentGate {

    /** 三类意图。原来只有「任务 / 非任务」，查询类被误判成闲聊扔给了端侧小模型 */
    enum class Kind { TASK, QUERY, CHAT }

    /** 查询类：要的是信息，不是动作。走 QueryEngine，不碰屏幕 */
    private val QUERY_HINTS = listOf(
        "查一下", "查查", "查询", "搜一下", "看看", "多少", "几号", "几点",
        "怎么样", "怎么走", "是什么", "是谁", "为什么", "行情", "股价", "指数",
        "涨了", "跌了", "天气", "新闻", "快递", "汇率", "油价", "限行",
    )

    /** 能落到具体设备动作上的动词 */
    private val TASK_VERBS = listOf(
        "打开", "启动", "运行", "关闭", "关掉", "退出",
        "点击", "点一下", "点开", "按一下", "长按", "选择",
        "输入", "填", "搜索", "搜一下", "查找",
        "返回", "回到", "退出到", "滑", "滚动", "翻页",
        "设置", "调", "调成", "开启", "关掉开关", "静音",
        "发消息", "发送", "拨号", "打电话", "分享",
        "截图", "截屏", "录屏",
        "安装", "卸载", "删除",
        // 系统导航类：用户常说「回桌面」，不带"打开"这种动词
        "桌面", "主页", "通知栏", "最近任务", "多任务",
        // 系统能力直调类：「声音大一点」「亮度小一点」没有"打开/点击"，但有明确的可执行意图
        "音量", "声音", "亮度", "屏幕亮", "大声", "小声",
        "调大", "调小", "调高", "调低", "静音", "免打扰",
        // 媒体类：「播放晴天」既没有动词也不含设备名词，最容易漏
        "播放", "放首", "放一首", "来首", "来一首", "听歌", "放歌",
        "暂停", "继续播放", "下一首", "上一首", "切歌",
        // 沙箱终端类（HANDOFF 坑 #13：每加一类执行能力必须同步这里）
        "终端", "沙箱", "命令", "跑个", "跑一下", "执行命",
    )

    /** 纯交流的开头，命中就不当成任务 */
    private val CHAT_PREFIX = listOf(
        "你是谁", "你叫", "你能做什么", "你会", "你好", "在吗", "在不在",
        "为什么", "怎么", "什么是", "介绍一下", "讲个", "说个",
        "今天", "明天", "现在几点", "天气",
    )

    fun isTask(text: String): Boolean = classify(text) == Kind.TASK

    /**
     * 三分类。
     *
     * 顺序有讲究：**先判任务再判查询**。「打开设置看看有什么」是任务（有操作动词），
     * 「查一下上证指数」是查询（没有操作动词）。反过来判会把前者误归查询。
     */
    fun classify(text: String): Kind {
        val t = text.trim()
        if (t.isEmpty()) return Kind.CHAT

        val startsWithChat = CHAT_PREFIX.any { t.startsWith(it) }
        val hasVerb = TASK_VERBS.any { t.contains(it) }
        val hasQuery = QUERY_HINTS.any { t.contains(it) }
        // 裸 shell 命令（"df -h"、"cat /etc/alpine-release"）没有中文动词，
        // 但首 token 命中沙箱工具白名单就是明确的任务意图
        val rawShell = HeuristicPlanner.looksLikeRawShellCommand(t)

        // 有操作动词 → 任务（哪怕同时有"查"，比如"打开XX查一下"）
        if (hasVerb || rawShell) return Kind.TASK
        // 纯交流开头且没有查询词 → 闲聊
        if (startsWithChat && !hasQuery) return Kind.CHAT
        // 有查询词 → 查询
        if (hasQuery) return Kind.QUERY
        return Kind.CHAT
    }

    /** 给用户看的解释 */
    fun reason(text: String): String = when {
        text.isBlank() -> "空输入"
        classify(text) == Kind.TASK -> "操作指令"
        classify(text) == Kind.QUERY -> "信息查询"
        else -> "看起来是交流而不是操作指令"
    }
}
