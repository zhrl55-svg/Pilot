package com.pilot.agent.assistant.routing

/**
 * 默认路由种子。
 *
 * 移植自 Minis 的 `DefaultAssistantRoutes`，按 Pilot 的 v1 能力裁剪，
 * 并把 Pilot 遗留匹配器（`tool/LocalToolRuntime.kt`）里已经在真机验证过的
 * 关键词收进来 —— 一份路由表，两边共用。
 *
 * 种子只提供**默认值**：用户在设置里改过的路由不会被种子覆盖
 * （见 [RouteStore.migrate]）。
 */
object DefaultRoutes {

    /**
     * 种子版本。
     *
     * v2：新增 `tool.memory-list`（memory_get/list）——「你记得什么」列全部，
     * 和遗留链路的 memory_list 对齐（v1 走搜索，结果不一样）。
     */
    // 3：内置路由匹配字段随版本刷新（见 migrate）；2 → 3 时同步了提醒口语词表
    const val SEED_VERSION = 3

    private fun r(
        id: String,
        label: String,
        category: String,
        capability: String,
        action: String,
        priority: Int = 100,
        keywords: List<String> = emptyList(),
        allKeywords: List<String> = emptyList(),
        patterns: List<String> = emptyList(),
        exclude: List<String> = emptyList(),
        examples: List<String> = emptyList(),
        confirm: ConfirmMode = ConfirmMode.WRITE,
    ) = AssistantRoute(
        id = id,
        label = label,
        category = category,
        capability = capability,
        action = action,
        priority = priority,
        keywords = keywords,
        allKeywords = allKeywords,
        patterns = patterns,
        exclude = exclude,
        examples = examples,
        confirm = confirm,
    )

    /**
     * 「我们可以加个电量显示功能」这类句子是在**聊**这个能力，不是在调用它。
     * 逐能力排除这些词，比在路由层做统一的覆盖率判断更准。
     */
    private val TOPIC_EXCLUDES = listOf(
        "加个", "添加", "增加", "支持", "功能", "开发", "实现",
        "讨论", "方案", "接口", "原理", "设计", "优化", "排查", "日志",
    )

    /**
     * 讨论「写文件」这件事的句子不是写文件指令。用带语境的短语而不是裸词：
     * 真命令（「把日志写入文件」「修改 /tmp/a.txt」）照样命中本地。
     */
    private val FILE_WRITE_TOPIC_EXCLUDES = listOf(
        "写入经", "写入配置", "写进配置", "配置文件",
        "代码里", "代码中", "日志里", "日志中", "文档里", "文档中",
        "确认卡", "讨论", "方案", "接口", "实现",
    )

    /**
     * 「音量设置」是打开设置页，不是调音量；「接口 / 方案 / 实现」说明用户在
     * 聊这个能力。这些都留给原链路。
     */
    private val VOLUME_TOPIC_EXCLUDES = listOf(
        "音量设置", "声音设置", "打开设置", "设置页", "设置里", "设置界面", "系统设置",
        "声音模式", "铃声设置", "设置铃声", "更换铃声", "换铃声",
        "讨论", "方案", "接口", "代码", "实现", "开发", "功能", "原理",
    )

    val all: List<AssistantRoute> = listOf(
        // ── app_launch ────────────────────────────────────────────────
        r(
            "life.app-open", "打开应用", "life", "app_launch", "open",
            priority = 70,
            keywords = listOf("打开应用", "启动应用", "启动app", "打开app", "launch app"),
            patterns = listOf(
                """^(?:帮我|给我|请|麻烦)?\s*(?:打开|启动|运行)\s*([\p{L}0-9_·\s]{1,12})$""",
            ),
            // 「打开网页」归浏览器扩展，「打开文件」归文件能力，
            // 「打开截屏设置」「音量设置」是设置页，不是「打开某个 App」。
            exclude = listOf(
                "打开网页", "打开网址", "浏览器", "打开文件",
                "打开截屏设置", "截屏设置", "音量设置", "声音设置",
                "设置页", "设置界面", "系统设置", "网页设置",
            ),
            examples = listOf("打开设置", "启动微信"),
        ),

        // ── volume ────────────────────────────────────────────────────
        r(
            "life.volume-set", "设置音量", "life", "volume", "set",
            priority = 160,
            keywords = listOf(
                "音量调到", "音量调至", "音量调成", "音量设为", "音量设成", "音量改成",
                "音量最大", "音量最小", "音量最响", "音量最轻", "媒体音量", "音乐音量",
                "把音量", "铃声音量", "铃声调到", "铃声最大", "铃声最小",
                "声音调到", "声音最大", "声音最小",
            ),
            patterns = listOf(
                """(?:音量|声音|响度|铃声).{0,8}(?:调到|调至|调成|设成|设为|设置为|改成|改为|变成|变到)\s*(?:百分之[零一二两三四五六七八九十百]{1,4}|\d{1,3}\s*(?:%|$|[,.!?]))""",
                """(?:音量|声音|响度|铃声)\s*(?:百分之[零一二两三四五六七八九十百]{1,4}|\d{1,3}\s*%)""",
                """(?:音量|声音|响度|铃声).{0,6}(?:最大|最响|最轻|最小)""",
            ),
            exclude = VOLUME_TOPIC_EXCLUDES,
            examples = listOf("音量调到百分之六十", "把铃声调到 50%", "音量调到最大"),
            confirm = ConfirmMode.NEVER,
        ),
        r(
            "life.volume-adjust", "调整音量", "life", "volume", "adjust",
            priority = 160,
            keywords = listOf(
                "调大音量", "调小音量", "音量调大", "音量调小", "音量加大", "音量减小",
                "音量增大", "音量降低", "音量调高", "音量调低", "声音调大", "声音调小",
                "声音大一点", "声音小一点", "声音大点", "声音小点", "大声点", "小声点",
                "大点声", "小点声",
            ),
            patterns = listOf(
                """(?:音量|声音|响度|铃声).{0,6}(?:调大|调小|增大|减小|加大|提高|升高|上调|降低|调低|下调|大一点|小一点|大一些|小一些|大点|小点|大很多|小很多|高一点|低一点)""",
                """(?:调大|调小|增大|减小|加大|提高|升高|上调|降低|调低|下调|大点声|小点声|大声点|小声点).{0,6}(?:音量|声音|响度)""",
                """^(?:帮我|给我|请|麻烦)?\s*(?:把)?\s*(?:音量|声音|铃声)?\s*(?:调大|调小|增大|减小|加大|提高|升高|上调|降低|调低|下调|大点声|小点声|大声点|小声点)\s*(?:一点|一些|点儿|点|很多|大幅|大量)?\s*$""",
            ),
            exclude = VOLUME_TOPIC_EXCLUDES,
            examples = listOf("音量调大一点", "音量调小 20%", "大点声"),
            confirm = ConfirmMode.NEVER,
        ),
        r(
            "life.volume-status", "音量状态", "life", "volume", "status",
            priority = 165,
            keywords = listOf(
                "音量多少", "当前音量", "现在音量", "音量是", "音量多大", "声音多大",
                "音量大小", "音量状态", "铃声音量", "媒体音量", "音乐音量",
            ),
            patterns = listOf(
                """(?:音量|声音|铃声).{0,4}(?:多少|多大|几档|是几|当前|现在|状态)""",
            ),
            exclude = VOLUME_TOPIC_EXCLUDES,
            examples = listOf("现在音量多少", "媒体音量是几档"),
            confirm = ConfirmMode.NEVER,
        ),

        // ── device ────────────────────────────────────────────────────
        r(
            "life.device-battery", "查询电量", "device", "device", "battery",
            priority = 150,
            keywords = listOf("电量", "电池", "还有多少电", "剩余电量", "battery", "battery level"),
            exclude = TOPIC_EXCLUDES,
            examples = listOf("电量还剩多少"),
        ),
        r(
            "life.device-storage", "查询存储", "device", "device", "storage",
            priority = 150,
            keywords = listOf("存储空间", "剩余空间", "磁盘空间", "storage", "disk space"),
            exclude = TOPIC_EXCLUDES,
            examples = listOf("还剩多少存储空间"),
        ),
        r(
            "life.device-info", "设备信息", "device", "device", "info",
            priority = 140,
            keywords = listOf("设备信息", "手机型号", "系统版本", "device info", "android version"),
            exclude = TOPIC_EXCLUDES,
            examples = listOf("设备信息"),
        ),

        // ── media ─────────────────────────────────────────────────────
        r(
            "life.media-pause", "暂停播放", "media", "media", "pause",
            priority = 145,
            keywords = listOf("暂停播放", "暂停音乐", "pause music", "pause playback"),
        ),
        r(
            "life.media-resume", "继续播放", "media", "media", "resume",
            priority = 145,
            keywords = listOf("继续播放", "恢复播放", "resume music", "resume playback"),
        ),
        r(
            "life.media-stop", "停止播放", "media", "media", "stop",
            priority = 145,
            keywords = listOf("停止播放", "关闭音乐", "stop music", "stop playback"),
        ),
        r(
            "life.media-status", "播放状态", "media", "media", "status",
            priority = 140,
            keywords = listOf("播放状态", "音乐状态", "media status", "playback status"),
            exclude = TOPIC_EXCLUDES,
        ),
        r(
            "life.media-list", "播放列表", "media", "media", "list",
            priority = 135,
            keywords = listOf("播放列表", "媒体会话", "media sessions", "list playback"),
            exclude = TOPIC_EXCLUDES,
        ),
        r(
            "life.music-play", "播放音乐", "media", "media", "play",
            priority = 150,
            keywords = listOf(
                "播放音乐", "放首歌", "放一首", "放首歌儿", "来首歌", "来一首",
                "听首歌", "听一首", "唱一首", "放点歌", "play music", "play a song",
            ),
            patterns = listOf(
                """^(?:帮我|给我|请|帮忙|麻烦|能不能|可以|我想|我要|想|要)?\s*(?:播放|放|来|想听|要听|听|唱)\s*一?[首下]\s*[\p{L}0-9_·\s]{0,20}$""",
                """^(?:帮我|给我|请|帮忙|麻烦|能不能|可以)?\s*(?:播放|放)\s*[\p{L}0-9_·\s]{1,16}(?:的歌|歌曲|曲子|音乐)$""",
                """^(?:帮我|给我|请|帮忙|麻烦|能不能|可以)?\s*(?:播放|放)\s*[\p{L}0-9_·\s]{1,10}$""",
                """^(?:please\s+)?play\s+[\p{L}0-9_' -]{1,40}$""",
            ),
            exclude = listOf(
                "设计", "方案", "接口", "代码", "实现", "优化", "排查", "日志",
                "讨论", "开发", "架构", "重构", "原理", "为什么", "是什么",
                "逻辑", "播放器", "播放列表", "播放状态", "参数",
            ),
            confirm = ConfirmMode.NEVER,
        ),
        r(
            "life.music-next", "下一首", "media", "media", "next",
            priority = 146,
            keywords = listOf("下一首", "下一首歌", "换一首", "next track", "next song"),
        ),
        r(
            "life.music-prev", "上一首", "media", "media", "previous",
            priority = 146,
            keywords = listOf("上一首", "上一首歌", "previous track", "previous song"),
        ),

        // ── reminder ──────────────────────────────────────────────────
        r(
            "life.reminder", "创建提醒", "life", "reminder", "schedule",
            priority = 230,
            keywords = listOf("提醒我", "提醒一下", "到点提醒", "记得提醒", "remind me", "reminder"),
            exclude = listOf("闹钟", "alarm"),
            examples = listOf("30分钟后提醒我开会", "remind me at 9 to call mom"),
        ),
        r(
            "life.reminder-list", "提醒列表", "life", "reminder", "list",
            priority = 195,
            keywords = listOf(
                "提醒列表", "我的提醒", "查看提醒", "打开提醒",
                // 口语疑问式：真机实测「我有提醒吗」原来 no-match，落到 Agent 去翻通知栏
                "有提醒吗", "有提醒么", "有什么提醒", "有哪些提醒", "还有提醒",
                "我有提醒", "提醒有没有", "查看我的提醒",
                "list reminders", "show reminders",
            ),
            // 排除"提醒我…"这种创建语义，交给高优先级的 schedule
            exclude = listOf("提醒我", "记得提醒"),
        ),
        r(
            "life.reminder-cancel", "取消提醒", "life", "reminder", "cancel",
            priority = 205,
            keywords = listOf("取消提醒", "删掉提醒", "cancel reminder"),
        ),

        // ── scheduled_task ────────────────────────────────────────────
        r(
            "life.scheduled-create", "创建定时任务", "life", "scheduled_task", "create",
            priority = 190,
            keywords = listOf("定时任务", "定时帮我", "每天帮我", "每周帮我", "schedule task", "every day help me"),
            patterns = listOf(
                """(?:每天|每日|每周|每个工作日|工作日).{0,10}(?:帮我|替我|提醒我|定时)""",
                """(?:every\s+day|daily|every\s+week|weekdays|every\s+(?:mon|tue|wed|thu|fri|sat|sun)[a-z]*).{0,30}(?:help\s+me|remind\s+me|run|send|summarize)""",
                """(?:remind\s+me|help\s+me).{0,24}(?:every\s+day|daily|every\s+week|weekdays|every\s+(?:mon|tue|wed|thu|fri|sat|sun)[a-z]*)""",
            ),
            examples = listOf("每天 8 点帮我查看财经新闻"),
        ),
        r(
            "life.scheduled-list", "定时任务列表", "life", "scheduled_task", "list",
            priority = 180,
            keywords = listOf("定时任务列表", "我的定时任务", "scheduled tasks", "list scheduled tasks"),
        ),
        r(
            "life.scheduled-cancel", "取消定时任务", "life", "scheduled_task", "cancel",
            priority = 200,
            keywords = listOf("取消定时任务", "删掉定时任务", "cancel scheduled task"),
        ),

        // ── memory ────────────────────────────────────────────────────
        r(
            "tool.memory-write", "保存记忆", "memory", "memory_write", "write",
            priority = 130,
            // 句首锚定：「你记住我说的」是在聊天，不是在下指令。
            patterns = listOf(
                """^(?:请|帮我|麻烦)?\s*(?:记住|牢记|记下来|记一下|存进记忆|别忘了)\s*[:：,，]?\s*(.+)$""",
            ),
            examples = listOf("记住我喜欢简短回答", "帮我记住 我的车在 B2 层"),
        ),
        r(
            "tool.memory-get", "查记忆", "memory", "memory_get", "search",
            priority = 125,
            keywords = listOf(
                "我说过", "我的记忆", "之前说过", "查记忆",
                "还记得", "你记得", "记不记得", "记忆里",
                "what did i say", "my memory",
            ),
        ),
        // 「你记得什么」问的是全部，不是搜关键词 —— 与遗留的 memory_list 对齐。
        // 关键词比 search 那条更长（"你记得什么" vs "你记得"），打分上稳赢。
        r(
            "tool.memory-list", "记忆列表", "memory", "memory_get", "list",
            priority = 126,
            keywords = listOf(
                "列出你记住", "你记住了什么", "查看长期记忆", "列出长期记忆",
                "记忆列表", "你记得什么",
            ),
        ),

        // ── files ─────────────────────────────────────────────────────
        r(
            "tool.file-read", "读文件", "files", "file_read", "read",
            priority = 120,
            keywords = listOf("读一下", "读取", "打开文件", "看看文件", "文件内容", "读文件", "read file", "show file"),
            examples = listOf("读一下 /sdcard/Download/a.txt"),
        ),
        r(
            "tool.file-write", "写文件", "files", "file_write", "write",
            priority = 120,
            keywords = listOf("写入", "写文件", "保存到", "存到", "新建文件", "write file", "save to"),
            exclude = FILE_WRITE_TOPIC_EXCLUDES,
            examples = listOf("把 hello 写入 /sdcard/Download/a.txt"),
        ),

        // ── screenshot ────────────────────────────────────────────────
        r(
            "tool.screenshot", "截屏", "tools", "screenshot", "take",
            priority = 120,
            keywords = listOf("截图", "截屏", "截个屏", "截一张屏", "screenshot", "screen shot"),
            exclude = listOf(
                "打开截屏设置", "关闭截屏设置", "截屏设置",
                "加个", "添加", "增加", "支持", "功能", "开发", "实现",
                "讨论", "方案", "接口", "代码", "原理", "设计",
            ),
            examples = listOf("截个屏"),
            confirm = ConfirmMode.NEVER,
        ),
    )

    /**
     * 迁移：只补种子里新增的 id，**不覆盖**用户改过的路由。
     */
    fun migrate(loaded: List<AssistantRoute>, fromVersion: Int): List<AssistantRoute> {
        if (fromVersion >= SEED_VERSION) return loaded
        val builtins = all.associateBy { it.id }
        // 内置路由的匹配字段（关键词/模式/排除词）必须跟随版本更新，
        // 否则修好的默认词表对已安装用户永远不生效——真机实测：改了
        // life.reminder-list 的关键词，设备上 11-18 的 routes.json 仍然 no-match。
        // 只保留用户可调的 enabled 与 priority，其余用新版内置值。
        val merged = loaded.map { local ->
            val builtin = builtins[local.id] ?: return@map local
            local.copy(
                label = builtin.label,
                category = builtin.category,
                keywords = builtin.keywords,
                allKeywords = builtin.allKeywords,
                patterns = builtin.patterns,
                exclude = builtin.exclude,
                examples = builtin.examples,
                confirm = builtin.confirm,
            )
        }
        val existing = merged.mapTo(hashSetOf()) { it.id }
        return merged + all.filter { it.id !in existing }
    }
}
