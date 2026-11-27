package com.pilot.agent

/**
 * 日志脱敏（审核报告 code.md S4）。
 *
 * 约定：日志只记录「结构信息」（长度、短哈希、类别），**不记录用户原文**——
 * 用户原话里可能有密码、验证码、联系人号码（SshTool 支持在原话里说密码）。
 *
 * - [text]：用户内容 → `label=<12字 #a1b2c3>`。只有 debug 包且显式打开
 *   [verboseContent] 时才输出前 60 字原文（方便本机排查），release 永远不落原文。
 * - [scrub]：对已经拼好的整条消息兜底打码：手机号/长数字串、邮箱、Cookie、
 *   Bearer/API Key、password=xxx 一类。用于无法区分结构的第三方文本（异常 message 等）。
 *
 * 建议 Dbg.write 落盘前统一调用 [scrub]（Dbg.kt 不在本次修改范围，见 fix/security.md）。
 */
object LogRedactor {

    /**
     * debug 包本机排查用：打开后 [text] 输出前 60 字原文。默认关闭；release 包无效。
     * 只能由调试入口（DebugCommands 的 `log_verbose`）在内存里临时打开，进程重启即恢复关闭。
     */
    @Volatile
    var verboseContent: Boolean = false

    private const val VERBOSE_TAKE = 60

    /** 用户内容只记长度 + 短哈希（同一句话哈希相同，可在日志里对照前后步骤）。 */
    fun text(label: String, value: String?): String {
        if (value == null) return "$label=null"
        if (BuildConfig.DEBUG && verboseContent) {
            return "$label=「${scrub(value.take(VERBOSE_TAKE))}${if (value.length > VERBOSE_TAKE) "…" else ""}」"
        }
        return "$label=<${value.length}字 #${shortHash(value)}>"
    }

    /** 号码类：只保留位数和末 2 位。 */
    fun phone(value: String?): String {
        if (value.isNullOrBlank()) return "<无号码>"
        val digits = value.filter { it.isDigit() }
        return if (digits.length <= 2) "<号码 ${digits.length}位>" else "<号码 ${digits.length}位 尾号${digits.takeLast(2)}>"
    }

    /** 异常只记类型（message 里经常带 URL 参数、Cookie 或原文）。 */
    fun error(t: Throwable?): String = t?.javaClass?.simpleName ?: "null"

    private val patterns: List<Pair<Regex, (MatchResult) -> String>> = listOf(
        // Cookie / 网易云登录态
        Pair(Regex("(?i)(MUSIC_U|__csrf|MUSIC_A|NMTID|cookie)\\s*[=:]\\s*[^;\\s,，]+"),
            { m: MatchResult -> "${m.groupValues[1]}=<已打码>" }),
        // Authorization: Bearer xxx / sk-xxx 一类密钥
        Pair(Regex("(?i)bearer\\s+[A-Za-z0-9._\\-]{8,}"), { _: MatchResult -> "Bearer <已打码>" }),
        Pair(Regex("\\bsk-[A-Za-z0-9_\\-]{8,}"), { _: MatchResult -> "sk-<已打码>" }),
        // password=xxx / 密码是xxx / 验证码 123456
        Pair(Regex("(?i)(password|passwd|pwd|token|api[_-]?key|secret)\\s*[=:：]\\s*\\S+"),
            { m: MatchResult -> "${m.groupValues[1]}=<已打码>" }),
        Pair(Regex("(密码|口令|验证码|动态码|校验码)\\s*(?:是|为|:|：)?\\s*\\S+"),
            { m: MatchResult -> "${m.groupValues[1]}<已打码>" }),
        // 邮箱
        Pair(Regex("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}"), { _: MatchResult -> "<邮箱>" }),
        // 手机号 / 座机 / 身份证 / 银行卡等 7 位以上连续数字（允许 +86、空格和短横线分隔）
        Pair(Regex("(?<![0-9A-Za-z])\\+?\\d[\\d \\-]{5,}\\d(?![0-9A-Za-z])"),
            { m: MatchResult -> phone(m.value) }),
    )

    /** 对整条消息兜底打码。 */
    fun scrub(message: String): String {
        var out = message
        for ((regex, replace) in patterns) {
            out = regex.replace(out, replace)
        }
        return out
    }

    private fun shortHash(value: String): String =
        Integer.toHexString(value.hashCode()).padStart(8, '0').take(6)
}
