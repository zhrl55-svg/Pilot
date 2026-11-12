package com.pilot.agent.extension

import android.content.Context
import android.net.Uri
import com.pilot.agent.Dbg
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

@Serializable
data class UserScriptStep(
    val action: String,
    val target: String = "",
    val text: String = "",
    val url: String = "",
    val server: String = "",
    val tool: String = "",
    val arguments: String = "{}",
    val max: Int = 6_000,
    val summary: String = "",
) {
    val hasSideEffect: Boolean
        get() = action in setOf("browser_click", "browser_type", "mcp_call")
}

@Serializable
data class UserScript(
    val id: String,
    val name: String,
    val description: String = "",
    val triggers: List<String> = emptyList(),
    val steps: List<UserScriptStep> = emptyList(),
) {
    val hasSideEffects: Boolean get() = steps.any { it.hasSideEffect }
}

/**
 * Local extension store for declarative browser/MCP workflows.
 *
 * Files live in the same host directory mounted at /var/minis/skills, so users
 * can import them from the settings screen or place them there with a file tool.
 */
class UserScriptStore(private val dir: File) {

    private val json = Json {
        ignoreUnknownKeys = false
        prettyPrint = true
    }

    init {
        dir.mkdirs()
    }

    fun all(): List<UserScript> = runCatching {
        dir.listFiles { f -> f.isFile && f.extension.equals("json", ignoreCase = true) }
            .orEmpty()
            .sortedBy { it.name.lowercase() }
            .mapNotNull(::readAndValidate)
    }.getOrDefault(emptyList())

    fun import(context: Context, uri: Uri): Result<UserScript> = runCatching {
        val size = context.contentResolver.openAssetFileDescriptor(uri, "r")
            ?.use { it.length }
            ?: -1L
        require(size <= MAX_FILE_BYTES) { "脚本文件不能超过 256 KB" }
        val name = queryDisplayName(context, uri)
        val text = context.contentResolver.openInputStream(uri)?.use(::readUtf8Limited)
            ?: error("无法读取脚本文件")
        require(text.toByteArray().size <= MAX_FILE_BYTES) { "脚本文件不能超过 256 KB" }
        val parsed = json.decodeFromString(UserScript.serializer(), text)
        val script = validate(parsed, fallbackId = name.substringBeforeLast('.'))
        write(script)
        script
    }

    fun delete(id: String): Boolean {
        val target = all().firstOrNull { it.id == id } ?: return false
        val file = File(dir, "${safeId(target.id)}.json")
        return file.delete()
    }

    fun match(goal: String): UserScript? {
        val text = goal.trim().lowercase()
        if (text.isEmpty()) return null
        return all()
            .mapNotNull { script ->
                val score = script.triggers.maxOfOrNull { raw ->
                    val trigger = raw.trim().lowercase()
                    when {
                        trigger.length < 2 -> 0
                        text == trigger -> 100
                        text.contains(trigger) -> 70 + trigger.length
                        else -> 0
                    }
                } ?: 0
                if (score > 0) script to score else null
            }
            .maxByOrNull { it.second }
            ?.first
    }

    private fun write(script: UserScript) {
        dir.mkdirs()
        val target = File(dir, "${safeId(script.id)}.json")
        val tmp = File(dir, ".${safeId(script.id)}.json.tmp")
        tmp.writeText(json.encodeToString(script))
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    private fun readAndValidate(file: File): UserScript? = runCatching {
        require(file.length() <= MAX_FILE_BYTES) { "文件过大" }
        val parsed = json.decodeFromString(UserScript.serializer(), file.readText())
        validate(parsed, fallbackId = file.nameWithoutExtension)
    }.onFailure {
        Dbg.w(TAG, "忽略无效用户脚本 ${file.name}: ${it.message}")
    }.getOrNull()

    private fun validate(source: UserScript, fallbackId: String): UserScript {
        val id = source.id.trim().ifBlank { fallbackId }
            .replace(Regex("[^A-Za-z0-9_.-]"), "_")
            .take(80)
        require(id.isNotBlank()) { "脚本 id 为空" }
        require(source.name.isNotBlank()) { "脚本 name 为空" }
        val triggers = source.triggers
            .map { it.trim() }
            .filter { it.length >= 2 }
            .distinct()
            .take(30)
        require(triggers.isNotEmpty()) { "至少需要一个触发器" }
        require(source.steps.isNotEmpty()) { "脚本没有步骤" }
        require(source.steps.size <= MAX_STEPS) { "脚本步骤不能超过 $MAX_STEPS 个" }
        val steps = source.steps.map { step ->
            require(step.action in ALLOWED_ACTIONS) { "不支持的动作：${step.action}" }
            when (step.action) {
                "browser_open" -> {
                    val url = step.url.ifBlank { step.target }
                    require(url.startsWith("https://") || url.startsWith("http://")) {
                        "browser_open 只允许 HTTP/HTTPS 地址"
                    }
                    step
                }
                "browser_click" -> {
                    val locator = step.target.ifBlank { step.text }
                    require(locator.isNotBlank()) { "browser_click 缺少目标" }
                    step
                }
                "browser_type" -> {
                    require(step.target.isNotBlank()) { "browser_type 缺少输入框定位" }
                    step
                }
                "mcp_tools" -> {
                    require(step.server.isNotBlank()) { "mcp_tools 缺少 server" }
                    step
                }
                "mcp_call" -> {
                    require(step.server.isNotBlank() && step.tool.isNotBlank()) {
                        "mcp_call 缺少 server 或 tool"
                    }
                    runCatching {
                        json.parseToJsonElement(step.arguments.ifBlank { "{}" }).jsonObject
                    }.getOrElse { error("mcp_call arguments 必须是 JSON 对象") }
                    step
                }
                "browser_read" -> step.copy(max = step.max.coerceIn(500, 20_000))
                else -> step
            }
        }
        return source.copy(
            id = id,
            name = source.name.trim().take(80),
            description = source.description.trim().take(300),
            triggers = triggers,
            steps = steps,
        )
    }

    private fun queryDisplayName(context: Context, uri: Uri): String {
        return runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "user-script.json"
    }

    private fun readUtf8Limited(input: java.io.InputStream): String {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            out.write(buffer, 0, count)
            require(out.size() <= MAX_FILE_BYTES) { "脚本文件不能超过 256 KB" }
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private fun safeId(id: String): String =
        id.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(80).ifBlank { "script" }

    companion object {
        private const val TAG = "PilotUserScript"
        private const val MAX_FILE_BYTES = 256 * 1024
        private const val MAX_STEPS = 40
        private val ALLOWED_ACTIONS = setOf(
            "browser_open",
            "browser_read",
            "browser_click",
            "browser_type",
            "browser_scroll",
            "browser_back",
            "mcp_list",
            "mcp_tools",
            "mcp_call",
        )
    }
}
