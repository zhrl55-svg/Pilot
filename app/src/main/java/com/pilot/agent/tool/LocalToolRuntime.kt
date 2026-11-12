package com.pilot.agent.tool

import android.content.Context
import com.pilot.agent.Dbg
import com.pilot.agent.agent.Risk
import com.pilot.agent.perception.ScreenReader
import java.util.Locale
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * Shared natural-language router for local tools.
 *
 * This runs before the accessibility agent. File, screenshot, status, and
 * memory requests therefore still work when accessibility is unavailable.
 */
class LocalToolRuntime(
    context: Context,
    readerProvider: () -> ScreenReader?,
) {
    private val appContext = context.applicationContext
    private val permissions = ToolPermissionStore(appContext)
    private val audit = ToolAuditLog(appContext)
    private val files = FileTools(appContext, readerProvider)
    private val devices = DeviceTools(appContext)
    private val ssh = SshTool()
    val memory = MemoryStore(appContext)

    fun match(text: String): ToolInvocation? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val builtIn = matchScheduledTask(t)
            ?: matchMemory(t)
            ?: matchScreenshot(t)
            ?: matchDevice(t)
        if (builtIn != null) return builtIn
        if (!prefersAgentExtension(t)) matchFiles(t)?.let { return it }
        return ssh.match(t)
    }

    /**
     * Browser and MCP instructions often contain URLs or the word "读取",
     * which would otherwise be captured by the file-tool regexes.
     */
    fun prefersAgentExtension(text: String): Boolean {
        val value = text.trim()
        val hasUrl = Regex("https?://", RegexOption.IGNORE_CASE).containsMatchIn(value)
        val browserIntent = listOf("浏览器", "网页会话", "网页", "链接", "正文").any(value::contains)
        val mcpIntent = listOf("MCP", "mcp").any(value::contains)
        val scriptIntent = listOf("用户脚本", "运行脚本", "调用脚本").any(value::contains)
        return scriptIntent || mcpIntent || (hasUrl && browserIntent)
    }

    fun displayTextFor(text: String, invocation: ToolInvocation?): String =
        ssh.displayTextFor(text, invocation)

    fun policyFor(group: ToolGroup): ToolPolicy = permissions.policyFor(group)

    fun setPolicy(group: ToolGroup, policy: ToolPolicy) {
        permissions.setPolicy(group, policy)
    }

    fun policies(): Map<ToolGroup, ToolPolicy> = permissions.all()

    fun usageAccessGranted(): Boolean = devices.usageAccessGranted()

    fun allFilesAccessGranted(): Boolean = files.hasAllFilesAccess()

    fun memoryCount(): Int = memory.count()

    fun recentAudit(limit: Int = 30): List<ToolAuditEntry> = audit.recent(limit)

    fun deleteMemoryFromUi(id: Long): Boolean {
        val deleted = memory.deleteById(id)
        audit.record(
            "memory_delete_ui",
            ToolGroup.DELETE,
            "explicit_ui_confirmed",
            if (deleted) ToolStatus.SUCCESS else ToolStatus.ERROR,
            "删除单条长期记忆",
        )
        return deleted
    }

    suspend fun execute(text: String, confirmer: ToolConfirmer): ToolResult? {
        val invocation = match(text) ?: return null
        return execute(invocation, confirmer)
    }

    suspend fun execute(invocation: ToolInvocation, confirmer: ToolConfirmer): ToolResult {
        val policy = permissions.policyFor(invocation.group)
        return when (policy) {
            ToolPolicy.DENY -> {
                audit.record(
                    invocation.name,
                    invocation.group,
                    "policy_denied",
                    ToolStatus.DENIED,
                    invocation.summary,
                )
                ToolResult.denied("“${invocation.group.label}”工具已被你设为禁止。")
            }
            ToolPolicy.ASK -> {
                if (invocation.explicitAuthorized) return runAudited(invocation, "explicit")
                val approved = confirmer.confirm(invocation.confirmation, invocation.risk())
                if (!approved) {
                    audit.record(
                        invocation.name,
                        invocation.group,
                        "rejected",
                        ToolStatus.DENIED,
                        invocation.summary,
                    )
                    ToolResult.denied("已取消 ${invocation.summary}。")
                } else {
                    runAudited(invocation, "approved")
                }
            }
            ToolPolicy.ALLOW -> runAudited(invocation, "allowed")
        }
    }

    private suspend fun runAudited(
        invocation: ToolInvocation,
        decision: String,
    ): ToolResult {
        val result = runCatching {
            withTimeout(TIMEOUT_MS) {
                invocation.execute(appContext) { _, _ -> false }
            }
        }.getOrElse { error ->
            Dbg.w(TAG, "${invocation.name} failed: ${error.javaClass.simpleName}")
            if (error is TimeoutCancellationException) {
                ToolResult.error("工具执行超时，已停止。")
            } else {
                ToolResult.error("工具执行失败：${error.message?.take(160) ?: error.javaClass.simpleName}")
            }
        }
        val capped = result.copy(
            message = result.message.take(MAX_RESULT_CHARS),
            spoken = result.spoken.take(MAX_SPOKEN_CHARS),
        )
        audit.record(
            invocation.name,
            invocation.group,
            decision,
            capped.status,
            invocation.summary,
        )
        return capped
    }

    private fun matchScreenshot(text: String): ToolInvocation? {
        if (!text.contains("截图") && !text.contains("截屏")) return null
        if (text.contains("打开截屏设置") || text.contains("关闭截屏")) return null
        return invocation(
            name = "take_screenshot",
            group = ToolGroup.READ,
            summary = "截屏",
            confirmation = "允许 Pilot 截取当前屏幕吗？截图只保存在本机。",
        ) { _, _ -> files.takeScreenshot() }
    }

    private fun matchScheduledTask(text: String): ToolInvocation? {
        Regex("(?:取消|删除)定时任务[：:，,\\s]*(.*)").find(text)?.let { match ->
            val keyword = match.groupValues[1].trim()
            return invocation(
                "cancel_scheduled_task",
                ToolGroup.DELETE,
                "取消定时任务",
                if (keyword.isBlank()) "确认取消最早创建的定时任务吗？" else "确认取消包含“$keyword”的定时任务吗？",
            ) { _, _ ->
                ToolResult.success(ScheduledTaskManager.cancel(appContext, keyword))
            }
        }
        if (listOf("我的定时任务", "查看定时任务", "列出定时任务").any(text::contains)) {
            return invocation("list_scheduled_tasks", ToolGroup.READ, "查看定时任务") { _, _ ->
                ToolResult.success(ScheduledTaskManager.list(appContext))
            }
        }
        if (ScheduledTaskManager.handles(text)) {
            return invocation(
                "create_scheduled_task",
                ToolGroup.WRITE,
                "创建定时任务",
                "要创建这个定时任务吗？",
                explicitAuthorized = true,
            ) { _, _ ->
                ScheduledTaskManager.create(appContext, text)?.let { ToolResult.success(it) }
                    ?: ToolResult.error("没听懂时间或要执行的内容。示例：每天 8 点查看财经新闻。")
            }
        }
        return null
    }

    private fun matchDevice(text: String): ToolInvocation? {
        val lower = text.lowercase(Locale.ROOT)
        return when {
            listOf("电量", "剩余电", "电池还有", "电池状态").any(text::contains) ->
                invocation("device_battery", ToolGroup.READ, "查询电量") { _, _ ->
                    devices.battery()
                }
            listOf("网络状态", "网络怎么样", "连的什么网", "wifi状态", "wi-fi状态", "移动网络")
                .any(lower::contains) ->
                invocation("device_network", ToolGroup.READ, "查询网络") { _, _ ->
                    devices.network()
                }
            listOf("存储空间", "磁盘空间", "剩余空间", "内存还剩").any(text::contains) ->
                invocation("device_storage", ToolGroup.READ, "查询存储") { _, _ ->
                    devices.storage()
                }
            listOf("定位开了吗", "定位状态", "位置服务", "gps开了吗").any(lower::contains) ->
                invocation("device_location", ToolGroup.READ, "查询定位状态") { _, _ ->
                    devices.location()
                }
            listOf("蓝牙开了吗", "蓝牙状态").any(text::contains) ->
                invocation("device_bluetooth", ToolGroup.READ, "查询蓝牙状态") { _, _ ->
                    devices.bluetooth()
                }
            listOf("用了多久手机", "屏幕使用时间", "应用使用时长", "今天使用了多久", "使用统计")
                .any(text::contains) ->
                invocation("device_usage", ToolGroup.READ, "查询应用使用时长") { _, _ ->
                    devices.usageStats()
                }
            else -> null
        }
    }

    private fun matchMemory(text: String): ToolInvocation? {
        val storeMatch = Regex(
            "^(?:请|帮我|麻烦)?(?:记住|牢记|记下来)[：:，,\\s]*(.+)$"
        ).find(text)
        if (storeMatch != null) {
            val content = storeMatch.groupValues[1].trim()
            if (content.length >= 2) {
                return invocation(
                    "memory_remember",
                    ToolGroup.WRITE,
                    "新增长期记忆",
                    "要保存这条长期记忆吗？内容只会留在本机。",
                    explicitAuthorized = true,
                ) { _, _ ->
                    memory.remember(content)?.let {
                        ToolResult.success("记住了：${it.content}", "记住了。")
                    } ?: ToolResult.error("这条记忆为空、重复或过长，没有保存。")
                }
            }
        }

        val listHints = listOf(
            "列出你记住", "你记住了什么", "查看长期记忆", "列出长期记忆",
            "记忆列表", "你记得什么",
        )
        if (listHints.any(text::contains)) {
            return invocation("memory_list", ToolGroup.READ, "查看长期记忆") { _, _ ->
                val items = memory.all()
                ToolResult.success(
                    memory.formatList(items),
                    if (items.isEmpty()) "还没有保存长期记忆。" else "你让我记住 ${items.size} 条内容。",
                )
            }
        }

        val forgetMatch = Regex(
            "(?:忘掉|忘记|删除记忆)[：:，,\\s]*(?:关于)?(.+?)(?:的记忆)?$"
        ).find(text)
        if (forgetMatch != null) {
            val keyword = forgetMatch.groupValues[1].trim()
            return invocation(
                "memory_forget",
                ToolGroup.DELETE,
                "删除长期记忆",
                "要从本机永久删除与“$keyword”相关的记忆吗？",
            ) { _, _ ->
                val removed = memory.forget(keyword)
                if (removed.isEmpty()) {
                    ToolResult.success("没有找到匹配的长期记忆。")
                } else {
                    ToolResult.success(
                        memory.formatList(removed, "没有找到匹配的长期记忆。")
                            .replaceFirst("长期记忆", "已删除"),
                        "已删除 ${removed.size} 条相关记忆。",
                    )
                }
            }
        }

        val looksLikeRecall = listOf("还记得", "你记得", "记不记得", "记忆里").any(text::contains)
        if (looksLikeRecall) {
            return invocation("memory_search", ToolGroup.READ, "搜索长期记忆") { _, _ ->
                val items = memory.search(text, 5)
                if (items.isEmpty()) {
                    ToolResult.success("我没有找到相关长期记忆。")
                } else {
                    ToolResult.success(
                        memory.formatList(items),
                        "我找到 ${items.size} 条相关记忆：${items.joinToString("；") { it.content }.take(300)}",
                    )
                }
            }
        }
        return null
    }

    private fun matchFiles(text: String): ToolInvocation? {
        Regex("(?:复制)(?:文件|文件夹)?[：:\\s]*(.+?)\\s*(?:到|至)\\s*(.+)").find(text)?.let { match ->
            val source = stripFileWords(match.groupValues[1])
            val destination = stripFileWords(match.groupValues[2])
            return invocation(
                "copy_file",
                ToolGroup.WRITE,
                "复制文件",
                "确认把“$source”复制到“$destination”吗？",
            ) { _, _ -> files.copyFile(source, destination) }
        }
        Regex("(?:移动|挪动)(?:文件|文件夹)?[：:\\s]*(.+?)\\s*(?:到|至)\\s*(.+)").find(text)?.let { match ->
            val source = stripFileWords(match.groupValues[1])
            val destination = stripFileWords(match.groupValues[2])
            return invocation(
                "move_file",
                ToolGroup.WRITE,
                "移动文件",
                "确认把“$source”移动到“$destination”吗？",
            ) { _, _ -> files.moveFile(source, destination) }
        }
        Regex("(?:删除|删掉)(?:文件或文件夹|文件|文件夹|目录)[：:\\s]*(.+)").find(text)?.let { match ->
            val path = stripFileWords(match.groupValues[1])
            return invocation(
                "delete_file",
                ToolGroup.DELETE,
                "删除文件",
                "确认永久删除“$path”吗？此操作不能撤销。",
            ) { _, _ -> files.deleteFile(path) }
        }
        Regex("(?:分享|发送)(?:文件|这个文件)[：:\\s]*(.+)").find(text)?.let { match ->
            val path = stripFileWords(match.groupValues[1])
            return invocation(
                "share_file",
                ToolGroup.SHARE,
                "分享文件",
                "确认打开系统分享面板，分享“$path”吗？",
            ) { _, _ -> files.shareFile(path) }
        }
        Regex("(?:打开)(?:文件|这个文件)[：:\\s]*(.+)").find(text)?.let { match ->
            val path = stripFileWords(match.groupValues[1])
            return invocation(
                "open_file",
                ToolGroup.SHARE,
                "打开文件",
                "确认让系统应用打开“$path”吗？",
            ) { _, _ -> files.openFile(path) }
        }
        Regex("(?:读取文件|读一下文件|看一下文件|查看文件|读取)[：:\\s]*(.+)").find(text)?.let { match ->
            val path = stripFileWords(match.groupValues[1])
            return invocation("read_file", ToolGroup.READ, "读取文件") { _, _ ->
                files.readFile(path)
            }
        }
        Regex("(?:写入文件|创建文件)[：:\\s]*(.+?)\\s*(?:内容|正文)[：:\\s]*(.*)", RegexOption.DOT_MATCHES_ALL)
            .find(text)?.let { match ->
                val path = stripFileWords(match.groupValues[1])
                val content = match.groupValues[2]
                val overwrite = text.contains("覆盖")
                return invocation(
                    "write_file",
                    ToolGroup.WRITE,
                    "写入文件",
                    if (overwrite) {
                        "确认覆盖“$path”，写入 ${content.length} 个字符吗？"
                    } else {
                        "确认把 ${content.length} 个字符写入“$path”吗？同名文件存在时会停止。"
                    },
                ) { _, _ -> files.writeFile(path, content, overwrite) }
            }
        Regex("把(.+?)(?:写入|写到)(?:文件)?[：:\\s]*(.+)", RegexOption.DOT_MATCHES_ALL)
            .find(text)?.let { match ->
                val content = match.groupValues[1].trim()
                val path = stripFileWords(match.groupValues[2])
                val overwrite = text.contains("覆盖")
                return invocation(
                    "write_file",
                    ToolGroup.WRITE,
                    "写入文件",
                    if (overwrite) {
                        "确认覆盖“$path”，写入 ${content.length} 个字符吗？"
                    } else {
                        "确认把 ${content.length} 个字符写入“$path”吗？同名文件存在时会停止。"
                    },
                ) { _, _ -> files.writeFile(path, content, overwrite) }
            }

        val hasListVerb = listOf("列出", "看看", "查看", "扫描", "有什么").any(text::contains)
        val hasFileHint = listOf(
            "文件", "文件夹", "目录", "下载", "文档", "图片", "照片", "音乐",
            "download", "document", "picture", "music",
        ).any { text.contains(it, ignoreCase = true) }
        if (hasListVerb && hasFileHint &&
            listOf("发送", "分享", "删除", "打开").none(text::contains)
        ) {
            val explicit = Regex("(?:列出|看看|查看|扫描|有什么)[：:\\s]*(?:文件或文件夹|文件|文件夹|目录)?[：:\\s]*(.*)")
                .find(text)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            val cleaned = stripFileWords(explicit)
            val knownDirectory = files.defaultDirectoryFor(cleaned)
            val path = if (knownDirectory.isNotBlank() &&
                listOf("目录", "文件夹", "里的文件", "中的文件").any(cleaned::contains)
            ) {
                knownDirectory
            } else {
                cleaned.ifBlank { files.defaultDirectoryFor(text) }
            }
            return invocation("list_files", ToolGroup.READ, "查看文件夹") { _, _ ->
                files.listFiles(path.ifBlank { files.rootDirectory() })
            }
        }
        return null
    }

    private fun stripFileWords(raw: String): String =
        raw.trim()
            .trim('"', '\'', '“', '”', '‘', '’', '：', ':', '，', ',', '。', '.', ' ')
            .removeSuffix("这个文件")
            .removeSuffix("的文件")
            .removeSuffix("文件")
            .trim()

    private fun invocation(
        name: String,
        group: ToolGroup,
        summary: String,
        confirmation: String = summary,
        explicitAuthorized: Boolean = false,
        execute: suspend (Context, ToolConfirmer) -> ToolResult,
    ) = ToolInvocation(name, group, summary, confirmation, explicitAuthorized, execute)

    companion object {
        private const val TAG = "PilotLocalTools"
        private const val TIMEOUT_MS = 20_000L
        private const val MAX_RESULT_CHARS = 16_000
        private const val MAX_SPOKEN_CHARS = 600
    }
}
