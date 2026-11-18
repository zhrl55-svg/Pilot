package com.pilot.agent.assistant.exec

import com.pilot.agent.assistant.routing.CapabilityRegistry
import com.pilot.agent.assistant.routing.CapabilityRisk
import com.pilot.agent.assistant.routing.RouteDecision

/**
 * 执行前的安全闸。
 *
 * ## 为什么本地执行要单独过一遍安全
 *
 * 云端路径里，命令是模型看着整段对话决定的；端侧路径里，参数是
 * 「用户刚说的那句话」+ 规则拼出来的。所以端侧多一条铁律：
 *
 * > **端侧要写出去的内容，必须逐词来自用户自己的原话。**
 *
 * 这条由逐词/子序列校验兜底 —— 模型把 `ls -la` 截成 `ls`、把占位符抄成
 * 「示例值」，都会在这一层被丢掉。
 *
 * 移植自 Minis 的 `LocalAssistantRouter.isSafe`，按 Pilot 的 v1 能力裁剪。
 */
object CapabilitySafety {

    /** 标点：子序列比对时忽略。 */
    private const val PUNCTUATION = "，。；、！？：,.!?;:'\"()（）【】[]{}"

    /**
     * 逐字复制型槽位：值必须**原样**来自用户原话。
     *
     * 路径、网址、命令这类值不能改一个字 —— 改了就可能指向别的地方。
     */
    private val VERBATIM_SLOTS = setOf("path", "url", "command")

    /**
     * 子序列型槽位：措辞可以变，字不能凭空多。
     *
     * 写入正文、应用名、记忆内容都是「从用户那句话里摘出来的一段」，
     * 用子序列判定：「我喜欢喝咖啡」是「记住我喜欢喝咖啡」的子序列，
     * 而「家乐福」不是。
     */
    private val SUBSEQUENCE_SLOTS = setOf("query", "keywords", "content", "app")

    /**
     * 破坏性命令 —— 即使用户原话里有，也不该由端侧直接执行。
     *
     * 宁可多走一次原链路，也不要让端侧把 `rm -rf` 当成一条普通指令执行。
     * （shell 不在 v1 能力里，先把闸备好。）
     */
    private val DESTRUCTIVE = Regex(
        """(^|[\s;&|(])(rm|mv|cp|dd|mkfs|mkswap|chmod|chown|kill|killall|pkill|""" +
            """shutdown|reboot|halt|poweroff|insmod|rmmod|truncate|shred|wipe|""" +
            """sudo|su|doas|passwd|iptables|ufw|systemctl|service|apt|apk|yum|dnf|pip|npm)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** 重定向：写文件，不该走 shell。 */
    private val REDIRECT = Regex("""[<>]""")

    /** 管道到解释器：下载即执行。 */
    private val PIPE_TO_SHELL = Regex("""\|\s*(ba|z|k|da)?sh\b""", RegexOption.IGNORE_CASE)

    sealed class Verdict {
        object Allow : Verdict()
        data class Deny(val reason: String) : Verdict()
    }

    /**
     * @param decision 路由器的本地决策
     * @param userText 用户原话
     */
    fun check(decision: RouteDecision.Local, userText: String): Verdict {
        val args = decision.args

        // 逐字 / 子序列校验：值必须来自用户自己的话。
        args.keys().forEach { key ->
            val value = args.optString(key)
            if (value.isBlank()) return@forEach
            if (key in VERBATIM_SLOTS && !isVerbatim(value, userText)) {
                return Verdict.Deny("slot-not-verbatim:$key")
            }
            if (key in SUBSEQUENCE_SLOTS && !isDerivedFrom(value, userText)) {
                return Verdict.Deny("slot-not-derived:$key")
            }
        }

        when (decision.capability) {
            "file_write" -> {
                val path = args.optString("path")
                if (path.isBlank()) return Verdict.Deny("file-write-without-path")
                val content = args.optString("content")
                if (content.isBlank()) return Verdict.Deny("file-write-without-content")
            }
            "volume" -> {
                val percent = args.optInt("percent", 0)
                if (percent < -100 || percent > 100) return Verdict.Deny("volume-percent-out-of-range")
            }
            "app_launch" -> {
                if (args.optString("app").isBlank()) return Verdict.Deny("app-launch-without-name")
            }
            "shell_execute" -> {
                val command = args.optString("command")
                if (command.isBlank()) return Verdict.Deny("shell-without-command")
                if (REDIRECT.containsMatchIn(command)) return Verdict.Deny("shell-redirect")
                if (PIPE_TO_SHELL.containsMatchIn(command)) return Verdict.Deny("shell-pipe-to-shell")
                if (DESTRUCTIVE.containsMatchIn(command)) return Verdict.Deny("shell-destructive")
            }
            else -> Unit
        }

        return Verdict.Allow
    }

    /**
     * 破坏性能力一律要确认，**即使路由写的是 NEVER**。
     *
     * 路由是用户可改的，但覆盖文件、删除这类操作不该因为改了一个开关
     * 就变成静默执行。
     */
    fun requiresConfirmation(decision: RouteDecision.Local): Boolean {
        val risk = CapabilityRegistry
            .action(decision.capability, decision.action)?.risk
        return decision.needsConfirmation || risk == CapabilityRisk.HIGH
    }

    /**
     * 逐词校验：值的每个词都得在用户原话里出现过。
     *
     * 用「词」而不是「子串」：`l` 是 `ls -la` 的子串，但显然不是用户要的命令。
     */
    fun isVerbatim(value: String, userText: String): Boolean {
        val words = value.trim().split(WHITESPACE).filter { it.isNotBlank() }
        if (words.isEmpty()) return false
        val spoken = tokenize(userText)
        return words.all { w -> spoken.any { it.equals(w, ignoreCase = true) } }
    }

    /**
     * 子序列判定：「附近的超市」不是「附近有超市吗」的子串，
     * 但去掉空白标点后是它的子序列 —— 模型把「超市」摘出来是合法的，
     * 凭空造一个「家乐福」就不是。
     */
    fun isDerivedFrom(content: String, userText: String): Boolean {
        val c = content.filter { !it.isWhitespace() && it !in PUNCTUATION }
        if (c.isEmpty()) return false
        val u = userText.filter { !it.isWhitespace() && it !in PUNCTUATION }
        var i = 0
        for (ch in u) {
            if (ch == c[i]) {
                i++
                if (i == c.length) return true
            }
        }
        return false
    }

    private fun tokenize(text: String): List<String> =
        text.split(TOKEN_SPLIT).map { it.trim() }.filter { it.isNotBlank() }

    private val WHITESPACE = Regex("""\s+""")
    private val TOKEN_SPLIT = Regex("""[\s,，。；;、！？!?:：]+""")
}
