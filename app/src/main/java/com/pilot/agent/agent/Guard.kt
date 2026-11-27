package com.pilot.agent.agent

import android.content.Context
import com.pilot.agent.perception.ScreenSnapshot
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 安全护栏。
 *
 * 系统级 agent 最大的风险不是"做不成"，而是"做成了不该做的事"——
 * 误点付款、群发消息、把相册删了。所以风险判定必须在**执行前**、在**独立的组件里**做，
 * 不能指望模型自己守规矩。
 *
 * ## 2026-11-27 修订（审核 G-01~G-06、S2）
 * - 「确定 / 继续 / 同意 / 下一步」这类通用确认按钮本身不含风险词：改为结合**所在页面/弹窗的文字**判定
 *   （[ScreenSnapshot.contextText] 含不可点的说明文字，如「确认支付 ¥299？」）。
 * - swipe（右滑确认支付）、long_press 纳入判定。
 * - 终端命令改**白名单 + 分级**（[CommandRisk]）：只读放行、其余确认、危险强确认（两次确认）。
 * - mcp_call、script_run 一律确认（不再按工具名黑名单猜）。
 * - 英文风险词按单词边界匹配，viewId 先按 `_` `/` `:` `.` 和驼峰拆词，避免 repost/payload/clearfix 误报；
 *   补齐繁体与同义词。
 * - [assess] 返回带原因的 [Assessment]，确认文案说清楚「为什么要确认」。
 */
class Guard(private val ctx: Context) {

    /**
     * @param reason 给用户看的确认理由（中文）；null 时用 [riskLabel]。
     * @param strong 需要「强确认」（AgentLoop 会连续确认两次）：危险终端命令等不可恢复操作。
     */
    data class Assessment(
        val risk: Risk,
        val reason: String? = null,
        val strong: Boolean = false,
    ) {
        companion object {
            val NONE = Assessment(Risk.NONE)
        }
    }

    /** 兼容旧调用方（HostBridge 等）：只要风险级别。 */
    fun riskOf(action: AgentAction, snap: ScreenSnapshot): Risk = assess(action, snap).risk

    /**
     * 风险判定。
     *
     * @param tainted 本次任务是否已经把外部不可信内容（网页正文、MCP/工具输出）喂给过规划器。
     *   为 true 时，原本放行的只读终端命令也要确认（防提示注入链：网页 → 终端）。
     */
    fun assess(action: AgentAction, snap: ScreenSnapshot, tainted: Boolean = false): Assessment {
        when (action.action) {
            "done", "wait", "ask", "unable" -> return Assessment.NONE
            "key", "scroll", "open" -> return Assessment.NONE
            "browser_close", "browser_back", "browser_scroll", "browser_read", "browser_open" ->
                return Assessment.NONE
            "mcp_list", "mcp_tools", "script_list" -> return Assessment.NONE
            "mcp_call" -> return Assessment(
                Risk.EXTERNAL,
                "调用外部 MCP 工具「${action.server.orEmpty()}/${action.tool.orEmpty()}」，可能产生外部副作用",
            )
            "script_run" -> return Assessment(
                Risk.EXTERNAL,
                "运行用户脚本「${action.target.orEmpty()}」，其中可能点击网页、填写内容或调用 MCP",
            )
            // AppFunction 的副作用由目标应用定义；缺少结构化风险元数据时按对外副作用处理。
            "app_func" -> return Assessment(Risk.EXTERNAL, "调用其他应用公开的功能，可能产生副作用")
            "systemop" -> return assessSystemOp(action, tainted)
        }

        if (action.action == "browser_type") {
            val field = action.target.orEmpty() + " " + action.summary.orEmpty()
            if (hasAny(field, sensitiveFieldWords)) return Assessment(Risk.SENSITIVE)
        }
        if (action.action == "browser_click") {
            // 网页按钮不在无障碍节点树里，target 通常就是按钮可见文字/选择器，必须独立参与风险判定。
            val label = action.target.orEmpty() + " " + action.summary.orEmpty()
            textRisk(label).takeIf { it != Risk.NONE }?.let { return Assessment(it) }
            // 看不清目标（css 选择器）或通用确认按钮（看不到网页语境）→ 按对外副作用强制确认。
            if (action.target.orEmpty().trim().startsWith("css:", ignoreCase = true)) {
                return Assessment(Risk.EXTERNAL, "网页按钮用选择器指定，看不清它是什么")
            }
            if (isConfirmish(action.target.orEmpty())) {
                return Assessment(Risk.EXTERNAL, "网页上的「${action.target.orEmpty().trim()}」是通用确认按钮，无法判断它确认的是什么")
            }
        }
        if (action.action == "shortcut" || action.action == "component") {
            val t = action.target.orEmpty().lowercase(Locale.ROOT)
            val payScheme = (t.startsWith("alipay") || t.startsWith("weixin://") || t.startsWith("wxpay") ||
                t.startsWith("upwrd") || t.startsWith("unionpay") || t.startsWith("mqqwallet")) &&
                listOf("pay", "scan", "qrcode", "transfer", "collect", "wallet").any { t.contains(it) }
            if (payScheme) return Assessment(Risk.MONEY, "这个直达链接会打开付款/扫码/转账类页面")
        }

        val targetNode = action.id?.let { snap.node(it) }
        if (action.action == "input" && targetNode != null &&
            (targetNode.password || hasAny(targetNode.text + " " + splitId(targetNode.viewId), sensitiveFieldWords))
        ) {
            return Assessment(Risk.SENSITIVE)
        }

        // 1) 动作自身 + 目标节点的文字
        val targetText = buildString {
            action.summary?.let { append(it).append(' ') }
            action.text?.let { append(it).append(' ') }
            action.target?.let { append(it).append(' ') }
            targetNode?.let { append(it.text).append(' ').append(splitId(it.viewId)) }
        }
        textRisk(targetText).takeIf { it != Risk.NONE }?.let { return Assessment(it) }

        // 2) 需要看页面语境的情形：
        //    - tap（坐标点击）/ swipe：看不清目标是什么（「右滑确认支付」）；
        //    - tap_node / long_press 的目标是「确定/继续/同意」这类通用确认按钮。
        //    只在这些情形扫页面文字——否则微信首页有个「支付」入口，点任何东西都会误报，
        //    用户会被确认弹窗逼疯（2026-11-10 回归实测踩到过「声音大一点」被判资金变动）。
        val label = targetNode?.text.orEmpty()
        val needContext = when (action.action) {
            "tap", "swipe" -> true
            "tap_node", "long_press" -> isConfirmish(label)
            else -> false
        }
        if (needContext) {
            val page = pageText(snap)
            val r = textRisk(page)
            if (r != Risk.NONE) {
                val hit = firstHit(page, r).orEmpty()
                val what = when (action.action) {
                    "tap" -> "点击坐标 (${action.x},${action.y})"
                    "swipe" -> "滑动操作"
                    else -> "按钮「${label.take(12)}」"
                }
                return Assessment(r, "$what 所在页面涉及「$hit」，${riskLabel(r)}风险需要你确认")
            }
        }
        return Assessment.NONE
    }

    private fun assessSystemOp(action: AgentAction, tainted: Boolean): Assessment {
        val arg = action.text.orEmpty()
        return when (action.target) {
            "call" -> Assessment(Risk.EXTERNAL, "将拨打电话给「${arg.take(20)}」")
            "terminal" -> when (CommandRisk.classify(arg)) {
                CommandRisk.Level.READ_ONLY ->
                    if (tainted) {
                        Assessment(Risk.EXTERNAL, "本次任务读取过网页或工具返回的外部内容，执行终端命令前需要你确认")
                    } else {
                        Assessment.NONE
                    }
                CommandRisk.Level.MODIFY ->
                    Assessment(Risk.EXTERNAL, "终端命令可能修改文件、联网或运行程序")
                CommandRisk.Level.DANGEROUS ->
                    Assessment(Risk.IRREVERSIBLE, "终端命令可能不可恢复地删除或覆盖数据", strong = true)
            }
            "settings_switch_off" ->
                if (hasAny(arg, sensitiveSwitchOff)) {
                    Assessment(Risk.EXTERNAL, "关闭「$arg」会影响联网、定位或找回手机")
                } else {
                    Assessment.NONE
                }
            "settings_switch_on" ->
                if (hasAny(arg, sensitiveSwitchOn)) {
                    Assessment(Risk.EXTERNAL, "开启「$arg」会断开网络连接")
                } else {
                    Assessment.NONE
                }
            else -> Assessment.NONE
        }
    }

    private fun pageText(snap: ScreenSnapshot): String = buildString {
        append(snap.contextText)
        for (n in snap.nodes) append(' ').append(n.text)
    }

    private fun firstHit(text: String, risk: Risk): String? {
        val words = when (risk) {
            Risk.MONEY -> moneyWords
            Risk.IRREVERSIBLE -> irreversibleWords
            Risk.EXTERNAL -> externalWords
            Risk.SENSITIVE -> sensitiveFieldWords
            Risk.NONE -> return null
        }
        return words.firstOrNull { containsWord(text, it) }
    }

    fun riskLabel(risk: Risk): String = when (risk) {
        Risk.MONEY -> "资金变动"
        Risk.IRREVERSIBLE -> "不可恢复的删除/退出"
        Risk.EXTERNAL -> "对外发送内容或外部副作用"
        Risk.SENSITIVE -> "敏感凭据或验证码"
        Risk.NONE -> "无"
    }

    /** 审计日志：每一次被确认/拒绝的高风险动作都留痕，出问题能回溯。 */
    fun audit(goal: String, action: AgentAction, risk: Risk, approved: Boolean) {
        runCatching {
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            // 高风险任务可能包含消息正文、收件人或凭据；审计只留动作摘要，不落原始请求。
            val safeGoal = "[用户任务内容已隐藏]"
            val line = "$ts\trisk=${risk.name}\tapproved=$approved\tgoal=$safeGoal\taction=${action.describe()}\n"
            val f = File(ctx.filesDir, "audit.log")
            // 上限 512KB：超过就只保留后一半，避免无限增长（审核 code.md 审计项）
            if (f.exists() && f.length() > AUDIT_MAX_BYTES) {
                val keep = f.readText().takeLast((AUDIT_MAX_BYTES / 2).toInt())
                f.writeText(keep.substringAfter('\n', keep))
            }
            f.appendText(line)
        }
    }

    fun recentAudit(limit: Int = 50): List<String> {
        val f = File(ctx.filesDir, "audit.log")
        if (!f.exists()) return emptyList()
        return f.readLines().takeLast(limit).reversed()
    }

    companion object {
        private const val AUDIT_MAX_BYTES = 512L * 1024

        internal val moneyWords = listOf(
            "支付", "付款", "付钱", "打款", "立即购买", "确认购买", "购买", "下单", "转账", "转帐",
            "充值", "打赏", "红包", "提交订单", "确认下单", "结算", "免密", "扣款", "付款码", "收款",
            "提现", "借款", "贷款", "还款",
            // 繁体
            "轉賬", "轉帳", "購買", "下單", "結算", "紅包", "付款碼", "提現", "還款",
            // 英文（按单词边界匹配）
            "pay", "payment", "checkout", "purchase", "buy now", "place order",
        )
        internal val irreversibleWords = listOf(
            "删除", "清空", "卸载", "格式化", "注销", "退出登录", "解散", "移除", "回收站", "解绑",
            "恢复出厂", "重置", "清除数据", "退出群聊", "永久删除", "撤销授权",
            // 繁体
            "刪除", "卸載", "註銷", "退出登錄", "解綁", "恢復出廠", "清除資料",
            // 英文
            "delete", "remove", "uninstall", "reset", "wipe", "erase", "clear data", "clear all",
            "factory reset", "sign out", "log out", "logout",
        )
        internal val externalWords = listOf(
            "发送", "发布", "发表", "分享", "群发", "评论", "拨号", "呼叫", "邀请", "转发", "提交", "投稿",
            // 繁体
            "發送", "發佈", "發布", "發表", "群發", "評論", "撥號", "邀請", "轉發", "傳送",
            // 英文
            "send", "post", "share", "publish", "submit", "invite", "tweet",
        )
        internal val sensitiveFieldWords = listOf(
            "密码", "验证码", "动态码", "口令", "支付密码", "密碼", "驗證碼",
            "security code", "password", "passcode", "otp", "pin",
        )
        private val sensitiveSwitchOff = listOf(
            "查找设备", "查找手机", "查找我的", "移动数据", "数据网络", "蜂窝", "WLAN", "Wi-Fi", "wifi",
            "定位", "位置信息", "位置服务",
        )
        private val sensitiveSwitchOn = listOf("飞行模式", "飛航模式", "airplane")

        private val confirmExact = setOf(
            "确定", "确认", "好", "好的", "继续", "同意", "完成", "提交", "是", "允许", "下一步", "我同意",
            "確定", "確認", "繼續", "允許",
            "ok", "okay", "yes", "confirm", "continue", "next", "agree", "allow", "accept", "done", "proceed",
        )
        private val confirmPrefixes = listOf("确认", "确定", "同意", "继续", "立即", "马上", "去", "確認", "確定", "繼續")

        /** 「确定 / 继续 / 同意 / 下一步 / 立即xx」这类本身不带语义的通用确认按钮。 */
        fun isConfirmish(label: String): Boolean {
            val t = label.trim().trimEnd('。', '！', '!', '>', '›', '»').replace(" ", "").lowercase(Locale.ROOT)
            if (t.isEmpty()) return false
            if (t in confirmExact) return true
            return t.length <= 8 && confirmPrefixes.any { t.startsWith(it) }
        }

        /**
         * 纯文字的风险级别（不看界面语境）。SkillStore 用它过滤不该沉淀的步骤。
         * 优先级：资金 > 不可恢复 > 对外。
         */
        fun textRisk(text: String): Risk {
            if (text.isBlank()) return Risk.NONE
            return when {
                hasAny(text, moneyWords) -> Risk.MONEY
                hasAny(text, irreversibleWords) -> Risk.IRREVERSIBLE
                hasAny(text, externalWords) -> Risk.EXTERNAL
                else -> Risk.NONE
            }
        }

        private fun hasAny(text: String, words: List<String>): Boolean =
            words.any { containsWord(text, it) }

        private val wordRegexCache = java.util.concurrent.ConcurrentHashMap<String, Regex>()

        /** 中文按子串；纯 ASCII 词按单词边界（不区分大小写），避免 repost/payload/clearfix 误报。 */
        internal fun containsWord(text: String, word: String): Boolean {
            val ascii = word.all { it.code < 128 }
            if (!ascii) return text.contains(word, ignoreCase = true)
            val re = wordRegexCache.getOrPut(word) {
                Regex("(?<![A-Za-z0-9])" + Regex.escape(word) + "(?![A-Za-z0-9])", RegexOption.IGNORE_CASE)
            }
            return re.containsMatchIn(text)
        }

        /** viewId 拆词：`com.tencent.mm:id/btnSendMsg` → `com tencent mm id btn Send Msg`。 */
        internal fun splitId(viewId: String): String =
            viewId.replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
                .replace(Regex("[_/:.\\-]+"), " ")
    }
}

/**
 * 终端命令风险分级（审核 G-04 / S2）：**白名单为主**，黑名单只用来识别「危险」档。
 *
 * - [Level.READ_ONLY]：首个命令（去掉 sudo/busybox/env 等包装）属于只读白名单，且没有写重定向、
 *   命令替换、管道进解释器 → 放行。
 * - [Level.DANGEROUS]：删除/覆盖/格式化/执行隐藏代码等 → 强确认（两次）。
 * - [Level.MODIFY]：其余一切（含未知命令、联网、追加写入）→ 确认。
 *
 * 宁可多问：引号里的 `;` `>` 也会被当成分隔/重定向处理，结果只会更保守。
 */
object CommandRisk {

    enum class Level { READ_ONLY, MODIFY, DANGEROUS }

    private val DANGER_PATTERNS = listOf(
        // rm 带 -r/-f/--recursive/--force（参数分开写、组合写都算），或删根/家目录/通配
        Regex("""(?i)\brm\b[^;&|\n]*\s-{1,2}[a-z]*[rf]"""),
        Regex("""(?i)\brm\b[^;&|\n]*\s(?:/|~|\*|/\*|\${'$'}HOME|~/\*)(?:\s|$)"""),
        Regex("""(?i)\bfind\b[^;&|\n]*\s-(?:delete|exec|execdir|ok|okdir|fprint\w*)\b"""),
        Regex("""(?i)\bxargs\b[^;&|\n]*\b(?:rm|shred|dd|mv|chmod|chown|truncate)\b"""),
        Regex("""(?i)\bdd\b[^;&|\n]*\bof="""),
        Regex("""(?i)\b(?:mkfs(?:\.\w+)?|mke2fs|wipefs|shred|fdisk|parted|sgdisk|format)\b"""),
        Regex("""(?i)\btruncate\b"""),
        Regex("""(?i)\b(?:chmod|chown|chgrp)\s+(?:-\w*\s+)*-\w*R"""),
        Regex("""(?i)\bmv\b[^;&|\n]*\s/dev/null\b"""),
        Regex(""":\(\)\s*\{"""),
        Regex("""(?i)\b(?:reboot|poweroff|halt|shutdown|passwd)\b"""),
        // 执行隐藏代码：sh -c / eval / python -c …；管道喂给解释器（curl … | sh）
        Regex("""(?i)\b(?:sh|bash|zsh|ash|dash|ksh)\s+-\w*c\b"""),
        Regex("""(?i)(?:^|[;&|\s])eval\s"""),
        Regex("""(?i)\b(?:python[23]?|perl|ruby|node|php|lua)\s+-\w*[ce]\b"""),
        Regex("""(?i)\|\s*(?:sudo\s+)?(?:busybox\s+)?(?:sh|bash|zsh|ash|dash|ksh|python[23]?|perl|ruby|node|php|lua)\b"""),
    )

    /** 无害重定向：丢到 /dev/null、2>&1。 */
    private val SAFE_REDIRECT = Regex("""(?:[0-9]?>{1,2}|&>)\s*/dev/null|[0-9]?>&[0-9]""")
    private val OVERWRITE = Regex("""(?<!>)>(?!>)""")
    private val SEPARATORS = Regex("""\|\||&&|[;|&\n]""")

    private val WRAPPERS = setOf("sudo", "busybox", "toybox", "command", "env", "nice", "nohup", "time", "exec", "!")

    private val READ_ONLY = setOf(
        "ls", "ll", "cat", "head", "tail", "df", "du", "uname", "ps", "echo", "printf", "pwd", "whoami",
        "date", "grep", "egrep", "fgrep", "rg", "wc", "stat", "file", "which", "whereis", "type", "id",
        "printenv", "uptime", "free", "hostname", "getprop", "dumpsys", "find", "sort", "uniq", "cut",
        "tr", "basename", "dirname", "realpath", "readlink", "md5sum", "sha1sum", "sha256sum", "sha512sum",
        "tree", "true", "false", "test", "[", "seq", "cal", "nproc", "lscpu", "lsblk", "mount", "cmp",
        "diff", "od", "hexdump", "xxd", "strings", "sed", "jq", "column", "tac", "nl", "rev", "fold",
        "ping", "netstat", "ss", "lsof", "ip", "ifconfig", "top", "history", "cd", "clear", "less", "more",
        "man", "help", "sleep", "apk", "git", "pm", "settings",
    )

    fun classify(raw: String): Level {
        val cmd = raw.trim()
        if (cmd.isEmpty()) return Level.READ_ONLY
        if (DANGER_PATTERNS.any { it.containsMatchIn(cmd) }) return Level.DANGEROUS

        var level = Level.READ_ONLY
        // 命令替换 / 进程替换：内容不透明，至少要确认（里面的危险写法已被上面的模式覆盖）
        if (cmd.contains("$(") || cmd.contains('`') || cmd.contains("<(") || cmd.contains(">(")) {
            level = Level.MODIFY
        }
        val noSafe = cmd.replace(SAFE_REDIRECT, " ")
        // 覆盖写（> file）会清空原文件内容：按危险处理；追加（>>）按修改处理
        if (OVERWRITE.containsMatchIn(noSafe)) return Level.DANGEROUS
        if (noSafe.contains(">>")) level = maxOf(level, Level.MODIFY)

        for (seg in cmd.split(SEPARATORS)) {
            val l = classifySegment(seg)
            if (l == Level.DANGEROUS) return Level.DANGEROUS
            level = maxOf(level, l)
        }
        return level
    }

    private fun classifySegment(seg: String): Level {
        val tokens = seg.trim().trim('(', ')', '{', '}').trim()
            .split(Regex("\\s+")).filter { it.isNotBlank() }.toMutableList()
        // 去掉 VAR=value 前缀与包装命令（sudo/busybox/env/nice/timeout N …）
        while (tokens.isNotEmpty()) {
            val t = tokens.first()
            when {
                t.matches(Regex("[A-Za-z_][A-Za-z0-9_]*=.*")) -> tokens.removeAt(0)
                t.substringAfterLast('/').lowercase(Locale.ROOT) in WRAPPERS -> tokens.removeAt(0)
                t.substringAfterLast('/').lowercase(Locale.ROOT) == "timeout" -> {
                    tokens.removeAt(0)
                    while (tokens.isNotEmpty() && (tokens.first().startsWith("-") || tokens.first().first().isDigit())) {
                        tokens.removeAt(0)
                    }
                }
                else -> break
            }
        }
        if (tokens.isEmpty()) return Level.READ_ONLY
        val first = tokens.first().substringAfterLast('/').lowercase(Locale.ROOT)
        if (first !in READ_ONLY) return Level.MODIFY
        val sub = tokens.getOrNull(1)?.lowercase(Locale.ROOT).orEmpty()
        val rest = tokens.drop(1).joinToString(" ").lowercase(Locale.ROOT)
        return when (first) {
            "sed" -> if (tokens.any { it == "-i" || it.startsWith("-i") || it == "--in-place" }) Level.MODIFY else Level.READ_ONLY
            "ip" -> if (Regex("""\b(?:set|add|del|delete|flush|change|replace|append)\b""").containsMatchIn(rest)) Level.MODIFY else Level.READ_ONLY
            "ifconfig" -> if (tokens.size > 2) Level.MODIFY else Level.READ_ONLY
            "mount" -> if (tokens.size > 1) Level.MODIFY else Level.READ_ONLY
            "date" -> if (tokens.any { it == "-s" || it.startsWith("--set") }) Level.MODIFY else Level.READ_ONLY
            "settings" -> if (sub == "get" || sub == "list") Level.READ_ONLY else Level.MODIFY
            "pm" -> if (sub == "list" || sub == "path" || sub == "dump") Level.READ_ONLY else Level.MODIFY
            "apk" -> if (sub in setOf("info", "list", "search", "policy", "stats", "version")) Level.READ_ONLY else Level.MODIFY
            "git" -> if (sub in setOf("status", "log", "diff", "show", "branch", "remote", "rev-parse")) Level.READ_ONLY else Level.MODIFY
            else -> Level.READ_ONLY
        }
    }
}
