package com.pilot.agent.extension

import android.content.Context
import com.pilot.agent.sandbox.SandboxTerminal
import java.io.File
import org.json.JSONObject

data class McpServerSummary(
    val name: String,
    val transport: String,
    val enabled: Boolean,
)

data class McpExecution(
    val ok: Boolean,
    val output: String,
    val exitCode: Int,
    val durationMs: Long,
)

/**
 * Host-side facade over the bundled minis-mcp-cli client.
 *
 * Every command runs inside the same PRoot guest as the user's terminal, so
 * OAuth bridges, secrets and server processes follow the sandbox permission
 * model rather than leaking into the main Android process.
 */
class McpClient(private val context: Context) {

    fun configuredServers(): List<McpServerSummary> {
        val file = File(context.filesDir, "minis-global/mcp-servers/servers.json")
        if (!file.isFile) return emptyList()
        return runCatching {
            val root = JSONObject(file.readText())
            val servers = root.optJSONObject("mcpServers") ?: JSONObject()
            servers.keys().asSequence().sorted().map { name ->
                val cfg = servers.optJSONObject(name) ?: JSONObject()
                val transport = when {
                    cfg.has("url") -> "HTTP"
                    cfg.has("command") -> "STDIO"
                    else -> "未知"
                }
                McpServerSummary(
                    name = name,
                    transport = transport,
                    enabled = cfg.optBoolean("enabled", true),
                )
            }.toList()
        }.getOrDefault(emptyList())
    }

    suspend fun listServers(): McpExecution = run(
        "minis-mcp-cli list --pretty",
        timeoutMs = COLD_START_TIMEOUT_MS,
    )

    suspend fun listTools(server: String): McpExecution {
        requireServerName(server)
        return run(
            "minis-mcp-cli tools ${shellQuote(server)} --pretty",
            timeoutMs = CALL_TIMEOUT_MS,
        )
    }

    suspend fun ping(server: String): McpExecution {
        requireServerName(server)
        return run(
            "minis-mcp-cli ping ${shellQuote(server)} --pretty",
            timeoutMs = CALL_TIMEOUT_MS,
        )
    }

    suspend fun call(
        server: String,
        tool: String,
        argumentsJson: String,
    ): McpExecution {
        requireServerName(server)
        requireToolName(tool)
        val args = normaliseArguments(argumentsJson)
        return run(
            "minis-mcp-cli call ${shellQuote(server)} ${shellQuote(tool)} " +
                "--input ${shellQuote(args)} --pretty",
            timeoutMs = CALL_TIMEOUT_MS,
        )
    }

    private suspend fun run(command: String, timeoutMs: Long): McpExecution {
        SandboxTerminal.init(context)
        val result = SandboxTerminal.run(context, command, timeoutMs)
        return McpExecution(
            ok = result.ok,
            output = result.output.trim(),
            exitCode = result.exitCode,
            durationMs = result.durationMs,
        )
    }

    private fun normaliseArguments(raw: String): String {
        val text = raw.trim().ifEmpty { "{}" }
        val obj = runCatching { JSONObject(text) }.getOrElse {
            throw IllegalArgumentException("MCP 参数必须是 JSON 对象")
        }
        return obj.toString()
    }

    private fun requireServerName(value: String) {
        require(value.matches(SERVER_RE)) { "非法 MCP 服务器名" }
    }

    private fun requireToolName(value: String) {
        require(value.matches(TOOL_RE)) { "非法 MCP 工具名" }
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    companion object {
        private const val COLD_START_TIMEOUT_MS = 420_000L
        private const val CALL_TIMEOUT_MS = 330_000L
        private val SERVER_RE = Regex("[A-Za-z0-9_.-]{1,96}")
        private val TOOL_RE = Regex("[A-Za-z0-9_.:/-]{1,160}")
    }
}
