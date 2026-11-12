package com.pilot.agent.tool

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Small, one-shot SSH connection tool.
 *
 * Credentials stay in memory for the prepared request and are never written to
 * the audit log. After a successful handshake the tool closes the session.
 */
internal class SshTool {
    private data class Pending(
        val host: String,
        val port: Int,
        val createdAt: Long,
    )

    private data class Target(
        val host: String,
        val port: Int,
        val username: String?,
        val password: String?,
    )

    private val lock = Any()

    @Volatile
    private var pending: Pending? = null

    fun match(text: String): ToolInvocation? {
        val raw = text.trim()
        if (raw.isEmpty()) return null

        val hostMatch = IPV4_WITH_OPTIONAL_PORT.find(raw)
        val hasSshHint = normalize(raw).contains("ssh")
        if (hostMatch != null && hasSshHint) {
            val target = targetFrom(raw, hostMatch) ?: return null
            if (target.username.isNullOrBlank() || target.password.isNullOrBlank()) {
                synchronized(lock) {
                    pending = Pending(target.host, target.port, System.currentTimeMillis())
                }
                return connectionInvocation(
                    target = target,
                    promptForCredentials = true,
                )
            }
            return connectionInvocation(target = target, promptForCredentials = false)
        }

        val waiting = synchronized(lock) {
            val current = pending
            if (current != null && System.currentTimeMillis() - current.createdAt > PENDING_TTL_MS) {
                pending = null
                null
            } else {
                current
            }
        } ?: return null

        val credentials = parseCredentialReply(raw) ?: return null
        return connectionInvocation(
            Target(waiting.host, waiting.port, credentials.first, credentials.second),
            promptForCredentials = false,
        )
    }

    fun displayTextFor(text: String, invocation: ToolInvocation?): String {
        if (invocation?.name != TOOL_CONNECT) return text
        val containsCredentialLabel = normalize(text).contains("密码") ||
            normalize(text).contains("password")
        val isCredentialReply = synchronized(lock) { pending != null } &&
            parseCredentialReply(text) != null
        if (!containsCredentialLabel && !isCredentialReply) return text

        val host = IPV4_WITH_OPTIONAL_PORT.find(text)?.value
        return if (host != null) {
            "SSH 连接 $host（凭据已隐藏）"
        } else {
            "SSH 凭据已隐藏"
        }
    }

    private fun connectionInvocation(
        target: Target,
        promptForCredentials: Boolean,
    ): ToolInvocation = ToolInvocation(
        name = TOOL_CONNECT,
        group = ToolGroup.SHARE,
        summary = "SSH 连接 ${target.host}:${target.port}",
        confirmation = "允许 Pilot 连接到 ${target.host}:${target.port} 吗？",
        explicitAuthorized = true,
    ) { _, _ ->
        if (promptForCredentials) {
            val reachable = withContext(Dispatchers.IO) { portReachable(target.host, target.port) }
            if (!reachable) {
                clearPending(target.host, target.port)
                return@ToolInvocation ToolResult.error(
                    "SSH 连接失败：${target.host}:${target.port} 不可访问。请检查地址、端口和手机网络。",
                )
            }
            return@ToolInvocation ToolResult.success(
                "目标 ${target.host}:${target.port} 可访问。请在下一条消息中提供“用户名 密码”；" +
                    "凭据只用于本次本机 SSH 握手，不会写入工具审计。",
                "目标可以访问。请告诉我用户名和密码，我会在手机上建立 SSH 连接。",
            )
        }

        val username = target.username.orEmpty()
        val password = target.password.orEmpty()
        val result = withContext(Dispatchers.IO) {
            testConnection(target.host, target.port, username, password)
        }
        if (result.status == ToolStatus.SUCCESS) clearPending(target.host, target.port)
        result
    }

    private fun targetFrom(text: String, hostMatch: MatchResult): Target? {
        val host = hostMatch.groups[1]?.value ?: return null
        if (!validIpv4(host)) return null
        val port = hostMatch.groups[2]?.value?.toIntOrNull() ?: 22
        if (port !in 1..65535) return null

        val explicitUser = USER_LABEL.find(text)?.groups?.get(1)?.value
        val explicitPassword = PASSWORD_LABEL.find(text)?.groups?.get(1)?.value
        if (!explicitUser.isNullOrBlank() && !explicitPassword.isNullOrBlank()) {
            return Target(host, port, explicitUser, explicitPassword.trim())
        }

        val afterHost = text.substring(hostMatch.range.last + 1)
            .trim(' ', '，', ',', '：', ':', '；', ';')
        val pair = parseCredentialReply(afterHost)
        return Target(host, port, pair?.first, pair?.second)
    }

    private fun parseCredentialReply(text: String): Pair<String, String>? {
        val trimmed = text.trim().trim('，', ',', '。', '；', ';')
        if (trimmed.isEmpty()) return null

        val labelled = LABELLED_CREDENTIALS.find(trimmed)
        if (labelled != null) {
            val user = labelled.groups[1]?.value?.trim().orEmpty()
            val password = labelled.groups[2]?.value?.trim().orEmpty()
            if (user.isNotBlank() && password.isNotBlank()) return user to password
        }

        val tokens = trimmed.split(Regex("\\s+"))
        if (tokens.size != 2) return null
        val user = tokens[0].trim()
        val password = tokens[1].trim()
        if (!USERNAME.matches(user) || password.length !in 1..256) return null
        return user to password
    }

    private fun testConnection(
        host: String,
        port: Int,
        username: String,
        password: String,
    ): ToolResult {
        var session: Session? = null
        val startedAt = System.nanoTime()
        return try {
            session = JSch().getSession(username, host, port).apply {
                setPassword(password)
                setConfig("PreferredAuthentications", "password,keyboard-interactive")
                setConfig("StrictHostKeyChecking", "accept-new")
                timeout = IO_TIMEOUT_MS
            }
            session.connect(CONNECT_TIMEOUT_MS)

            val channel = session.openChannel("exec") as ChannelExec
            val output = ByteArrayOutputStream()
            channel.setCommand(
                "printf 'PILOT_SSH_OK\\n'; " +
                    "printf 'USER='; (id -un 2>/dev/null || whoami); " +
                    "printf 'HOST='; hostname",
            )
            channel.setInputStream(null)
            channel.setErrStream(output)
            channel.outputStream = output
            channel.connect(IO_TIMEOUT_MS)
            channel.disconnect()

            val response = output.toString(Charsets.UTF_8.name()).trim()
            if (!response.contains("PILOT_SSH_OK")) {
                return ToolResult.error(
                    "SSH 认证成功，但远端没有返回预期结果。输出：${response.take(240).ifBlank { "(空)" }}",
                )
            }
            val elapsed = (System.nanoTime() - startedAt) / 1_000_000L
            val detail = response.lineSequence()
                .filter { it.startsWith("USER=") || it.startsWith("HOST=") }
                .joinToString("，") { it.replace('=', '：') }
            ToolResult.success(
                "SSH 连接成功：$username@$host:$port（${elapsed}ms）。" +
                    if (detail.isBlank()) "" else "\n$detail",
                "SSH 连接成功，登录用户是 $username。",
            )
        } catch (error: Throwable) {
            val message = error.message.orEmpty()
            val reason = when {
                message.contains("Auth fail", ignoreCase = true) ->
                    "用户名或密码认证失败"
                message.contains("timeout", ignoreCase = true) ->
                    "连接超时"
                message.contains("refused", ignoreCase = true) ->
                    "端口拒绝连接"
                message.contains("UnknownHostKey", ignoreCase = true) ->
                    "主机密钥校验失败"
                else -> "握手失败（${error.javaClass.simpleName}）"
            }
            ToolResult.error("SSH 连接失败：$reason。目标 $host:$port。")
        } finally {
            runCatching { session?.disconnect() }
        }
    }

    private fun portReachable(host: String, port: Int): Boolean = runCatching {
        java.net.Socket().use { socket ->
            socket.connect(java.net.InetSocketAddress(host, port), PORT_PROBE_TIMEOUT_MS)
            true
        }
    }.getOrDefault(false)

    private fun clearPending(host: String, port: Int) {
        synchronized(lock) {
            val current = pending
            if (current?.host == host && current.port == port) pending = null
        }
    }

    private fun normalize(text: String): String =
        text.lowercase(Locale.ROOT).replace(Regex("\\s+"), "")

    private fun validIpv4(host: String): Boolean =
        host.split('.').let { parts ->
            parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 }
        }

    companion object {
        const val TOOL_CONNECT = "ssh_connect"
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val IO_TIMEOUT_MS = 8_000
        private const val PORT_PROBE_TIMEOUT_MS = 2_500
        private const val PENDING_TTL_MS = 3 * 60_000L

        private val IPV4_WITH_OPTIONAL_PORT =
            Regex("""(?<![\d.])((?:\d{1,3}\.){3}\d{1,3})(?::(\d{1,5}))?(?![\d.])""")
        private val USER_LABEL =
            Regex("""用户名\s*[:：]?\s*([A-Za-z0-9._-]{1,64})""", RegexOption.IGNORE_CASE)
        private val PASSWORD_LABEL =
            Regex("""(?:密码|口令|password)\s*[:：]?\s*(\S.+?)\s*$""", RegexOption.IGNORE_CASE)
        private val LABELLED_CREDENTIALS = Regex(
            """用户名\s*[:：]?\s*([A-Za-z0-9._-]{1,64})\s*[,，;；]?\s*(?:密码|口令|password)\s*[:：]?\s*(.+)$""",
            RegexOption.IGNORE_CASE,
        )
        private val USERNAME = Regex("""[A-Za-z0-9._-]{1,64}""")
    }
}
