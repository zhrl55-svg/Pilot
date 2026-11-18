package com.pilot.agent.assistant.exec

import android.content.Context
import android.content.Intent
import com.pilot.agent.action.ActionCatalog
import com.pilot.agent.action.AppLaunchIndex
import com.pilot.agent.action.DeviceActions
import com.pilot.agent.action.ReminderManager
import com.pilot.agent.action.SystemOps
import com.pilot.agent.assistant.routing.RouteDecision
import com.pilot.agent.music.MusicPlayer
import com.pilot.agent.tool.LocalToolRuntime
import com.pilot.agent.tool.ScheduledTaskManager
import com.pilot.agent.tool.ToolGroup
import com.pilot.agent.tool.ToolInvocation
import com.pilot.agent.tool.ToolResult
import org.json.JSONObject

/**
 * 能力 → 执行器。
 *
 * **这里不实现任何新工具**：每一个能力都落到 Pilot 已有的执行器上
 * （文件、设备、记忆、提醒、定时任务、播放器、系统音量、应用启动），
 * 产物是现有契约 [ToolInvocation]，交给 `LocalToolRuntime.execute` 统一处理
 * 权限、确认、审计、超时。
 *
 * 拿不准的能力返回 null —— 交回原来的链路，绝不猜着执行。
 */
class CapabilityExecutor(
    private val context: Context,
    private val tools: LocalToolRuntime,
    private val appIndexProvider: () -> AppLaunchIndex? = { null },
    private val actionsProvider: () -> DeviceActions? = { null },
) {

    /**
     * @return 可执行的工具调用；null = 这个能力/动作本阶段不接，交回原链路。
     */
    fun invocation(decision: RouteDecision.Local, userText: String): ToolInvocation? {
        val args = decision.args
        return when (decision.capability) {
            "app_launch" -> appLaunch(args)
            "volume" -> volume(decision.action, args)
            "device" -> device(decision.action)
            "media" -> media(decision.action)
            "reminder" -> reminder(decision.action, userText)
            "scheduled_task" -> scheduledTask(decision.action, args, userText)
            "memory_write" -> memoryWrite(args)
            "memory_get" -> memoryGet(decision.action, userText)
            "file_read" -> fileRead(args)
            "file_write" -> fileWrite(args)
            "screenshot" -> screenshot()
            else -> null
        }
    }

    // ── app_launch ───────────────────────────────────────────────────
    private fun appLaunch(args: JSONObject): ToolInvocation? {
        val app = args.optString("app").trim()
        if (app.isBlank()) return null

        // 先看能不能真的打开：
        // 1) 系统设置页 / 系统动作 / deep link —— 不需要索引，最稳；
        // 2) 普通 App —— 要索引里有它的启动入口。
        //
        // ★ 两样都没有就**交回原链路**（返回 null），而不是回一句「没找到「X」」。
        //   原链路的 agent 还能在屏幕上找；快路径不能把用户的请求做死。
        //   索引没建好时（appIndexProvider() 为 null）尤其要这样。
        val catalog = ActionCatalog.resolve(app)
        val entry = if (catalog == null) appIndexProvider()?.resolve(app) else null
        if (catalog == null && entry == null) return null

        val summary = "打开 $app"
        return ToolInvocation(
            name = "open_app",
            group = ToolGroup.READ,
            summary = summary,
            confirmation = "允许打开「$app」吗？",
        ) { ctx, _ ->
            // 1) 系统设置页 / 系统动作 / deep link。
            if (catalog != null) {
                val intent = ActionCatalog.toIntent(catalog)
                return@ToolInvocation runCatching { ctx.startActivity(intent) }
                    .fold(
                        onSuccess = { ToolResult.success("已打开「${catalog.label}」") },
                        onFailure = { ToolResult.error("打开「${catalog.label}」失败：${it.message}") },
                    )
            }
            // 2) App 内入口：需要无障碍服务才能可靠启动。
            val launchable = entry ?: return@ToolInvocation ToolResult.error("没找到「$app」")
            val actions = actionsProvider()
            if (actions != null) {
                val ok = actions.launchComponent(launchable.pkg, launchable.component.className)
                return@ToolInvocation if (ok) {
                    ToolResult.success("已打开「${launchable.appLabel}」")
                } else {
                    ToolResult.error("没能打开「${launchable.appLabel}」。")
                }
            }
            // 3) 没有无障碍服务：退回包名主入口。
            val launch = ctx.packageManager.getLaunchIntentForPackage(launchable.pkg)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ?: return@ToolInvocation ToolResult.error("没找到「$app」的主入口。")
            runCatching { ctx.startActivity(launch) }
                .fold(
                    onSuccess = { ToolResult.success("已打开「${launchable.appLabel}」") },
                    onFailure = { ToolResult.error("打开「${launchable.appLabel}」失败：${it.message}") },
                )
        }
    }

    // ── volume ───────────────────────────────────────────────────────
    private fun volume(action: String, args: JSONObject): ToolInvocation? {
        val ops = SystemOps(context)
        return when (action) {
            "set" -> {
                val percent = args.optInt("percent", -1)
                if (percent < 0) return null
                ToolInvocation(
                    name = "set_volume",
                    group = ToolGroup.READ,
                    summary = "音量设为 $percent%",
                    confirmation = "允许把音量设为 $percent% 吗？",
                ) { _, _ ->
                    val message = ops.setVolume(percent)
                    if (message == null) ToolResult.error("音量没有调成功。")
                    else ToolResult.success(message)
                }
            }
            "adjust" -> {
                val delta = args.optInt("percent", 0)
                if (delta == 0) return null
                ToolInvocation(
                    name = "adjust_volume",
                    group = ToolGroup.READ,
                    summary = if (delta > 0) "音量调大 $delta%" else "音量调小 ${-delta}%",
                    confirmation = "允许调整音量吗？",
                ) { _, _ ->
                    val message = ops.adjustVolume(delta)
                    if (message == null) ToolResult.error("音量没有调成功。")
                    else ToolResult.success(message)
                }
            }
            "status" -> ToolInvocation(
                name = "volume_status",
                group = ToolGroup.READ,
                summary = "查看音量",
                confirmation = "允许查看当前音量吗？",
            ) { _, _ ->
                val current = ops.currentVolumePercent()
                if (current == null) ToolResult.unavailable("读不到当前音量。")
                else ToolResult.success("当前音量 $current%")
            }
            else -> null
        }
    }

    // ── device ───────────────────────────────────────────────────────
    private fun device(action: String): ToolInvocation? = when (action) {
        "battery" -> ToolInvocation(
            name = "device_battery",
            group = ToolGroup.READ,
            summary = "查询电量",
            confirmation = "允许查询电量吗？",
        ) { _, _ -> tools.devices.battery() }
        "storage" -> ToolInvocation(
            name = "device_storage",
            group = ToolGroup.READ,
            summary = "查询存储",
            confirmation = "允许查询存储空间吗？",
        ) { _, _ -> tools.devices.storage() }
        "info" -> ToolInvocation(
            name = "device_info",
            group = ToolGroup.READ,
            summary = "查看设备状态",
            confirmation = "允许查看设备状态吗？",
        ) { _, _ ->
            val battery = tools.devices.battery()
            val storage = tools.devices.storage()
            val network = tools.devices.network()
            val parts = listOf(battery.message, storage.message, network.message)
                .filter { it.isNotBlank() }
            ToolResult.success(parts.joinToString("\n"))
        }
        else -> null
    }

    // ── media ────────────────────────────────────────────────────────
    private fun media(action: String): ToolInvocation? {
        // 播放（按歌名/歌手/流派搜索）要走 Netease 检索流水线，那条链路在
        // AgentLoop 里；本阶段不重复实现，交回原链路。
        if (action == "play") return null
        return when (action) {
            "pause" -> ToolInvocation(
                name = "media_pause",
                group = ToolGroup.READ,
                summary = "暂停播放",
                confirmation = "允许暂停播放吗？",
            ) { _, _ ->
                val player = MusicPlayer.get(context)
                if (player.pause()) ToolResult.success("已暂停播放。")
                else ToolResult.unavailable("现在没有在播放。")
            }
            "resume" -> ToolInvocation(
                name = "media_resume",
                group = ToolGroup.READ,
                summary = "继续播放",
                confirmation = "允许继续播放吗？",
            ) { _, _ ->
                val player = MusicPlayer.get(context)
                player.resume()
                ToolResult.success("已继续播放。")
            }
            "next" -> ToolInvocation(
                name = "media_next",
                group = ToolGroup.READ,
                summary = "下一首",
                confirmation = "允许切到下一首吗？",
            ) { _, _ ->
                val player = MusicPlayer.get(context)
                if (player.next()) ToolResult.success("已切到下一首。")
                else ToolResult.unavailable("播放列表是空的。")
            }
            "previous" -> ToolInvocation(
                name = "media_previous",
                group = ToolGroup.READ,
                summary = "上一首",
                confirmation = "允许切到上一首吗？",
            ) { _, _ ->
                val player = MusicPlayer.get(context)
                if (player.previous()) ToolResult.success("已切到上一首。")
                else ToolResult.unavailable("播放列表是空的。")
            }
            "stop" -> ToolInvocation(
                name = "media_stop",
                group = ToolGroup.READ,
                summary = "停止播放",
                confirmation = "允许停止播放吗？",
            ) { _, _ ->
                MusicPlayer.get(context).stop()
                ToolResult.success("已停止播放。")
            }
            "status" -> ToolInvocation(
                name = "media_status",
                group = ToolGroup.READ,
                summary = "查看播放状态",
                confirmation = "允许查看播放状态吗？",
            ) { _, _ ->
                val player = MusicPlayer.get(context)
                val title = player.nowPlaying()
                if (title == null) ToolResult.success("现在没有在播放。")
                else ToolResult.success(
                    "正在播放：$title - ${player.currentArtist()}（第 ${player.currentIndex()}/${player.queueSize()} 首）"
                )
            }
            "list" -> ToolInvocation(
                name = "media_list",
                group = ToolGroup.READ,
                summary = "查看播放列表",
                confirmation = "允许查看播放列表吗？",
            ) { _, _ ->
                val player = MusicPlayer.get(context)
                val queue = player.queueSnapshot()
                if (queue.isEmpty()) ToolResult.success("播放列表是空的。")
                else ToolResult.success(
                    queue.joinToString("\n") { "${it.title} - ${it.artist}" }
                )
            }
            else -> null
        }
    }

    // ── reminder ─────────────────────────────────────────────────────
    /**
     * 提醒管理器只提供一个入口 [ReminderManager.handle]：它自己认「提醒我 /
     * 我的提醒 / 取消提醒」并解析时间与标题。这里只负责把请求交给它。
     *
     * 取消是破坏性操作 → 走 DELETE 组（默认每次询问），不当作「用户已经明确要求」。
     */
    private fun reminder(action: String, userText: String): ToolInvocation? = when (action) {
        "schedule" -> ToolInvocation(
            name = "create_reminder",
            group = ToolGroup.WRITE,
            summary = "创建提醒",
            confirmation = "允许创建这个提醒吗？",
            explicitAuthorized = true,
        ) { ctx, _ ->
            val message = ReminderManager.handle(ctx, userText)
            ToolResult.success(message)
        }
        "list" -> ToolInvocation(
            name = "list_reminders",
            group = ToolGroup.READ,
            summary = "查看提醒",
            confirmation = "允许查看提醒吗？",
        ) { ctx, _ ->
            val message = ReminderManager.handle(ctx, userText)
            ToolResult.success(message)
        }
        "cancel" -> ToolInvocation(
            name = "cancel_reminder",
            group = ToolGroup.DELETE,
            summary = "取消提醒",
            confirmation = "确认取消这个提醒吗？",
        ) { ctx, _ ->
            val message = ReminderManager.handle(ctx, userText)
            ToolResult.success(message)
        }
        else -> null
    }

    // ── scheduled_task ───────────────────────────────────────────────
    private fun scheduledTask(action: String, args: JSONObject, userText: String): ToolInvocation? =
        when (action) {
            "create" -> ToolInvocation(
                name = "create_scheduled_task",
                group = ToolGroup.WRITE,
                summary = "创建定时任务",
                confirmation = "要创建这个定时任务吗？",
                explicitAuthorized = true,
            ) { ctx, _ ->
                val message = ScheduledTaskManager.create(ctx, userText)
                    ?: "没听懂时间或要执行的内容。示例：每天 8 点查看财经新闻。"
                ToolResult.success(message)
            }
            "list" -> ToolInvocation(
                name = "list_scheduled_tasks",
                group = ToolGroup.READ,
                summary = "查看定时任务",
                confirmation = "允许查看定时任务吗？",
            ) { ctx, _ -> ToolResult.success(ScheduledTaskManager.list(ctx)) }
            "cancel" -> ToolInvocation(
                name = "cancel_scheduled_task",
                group = ToolGroup.DELETE,
                summary = "取消定时任务",
                confirmation = "确认取消这个定时任务吗？",
            ) { ctx, _ ->
                ToolResult.success(ScheduledTaskManager.cancel(ctx, args.optString("id")))
            }
            else -> null
        }

    // ── memory ───────────────────────────────────────────────────────
    private fun memoryWrite(args: JSONObject): ToolInvocation? {
        val content = args.optString("content").trim()
        if (content.isBlank()) return null
        return ToolInvocation(
            name = "memory_remember",
            group = ToolGroup.WRITE,
            summary = "新增长期记忆",
            confirmation = "要保存这条长期记忆吗？内容只会留在本机。",
            explicitAuthorized = true,
        ) { _, _ ->
            val memory = tools.memory.remember(content)
            if (memory == null) {
                ToolResult.error("这条记忆为空、重复或过长，没有保存。")
            } else {
                ToolResult.success("记住了：${memory.content}", "记住了。")
            }
        }
    }

    /**
     * 查记忆：`search` 搜相关条目，`list` 列全部。
     *
     * `list` 对齐遗留链路的 `memory_list`：同一批话术（"你记得什么" /
     * "列出你记住" …）在遗留侧走的是列全部，快路径之前一律当搜索，
     * 结果和用户预期不一致。
     */
    private fun memoryGet(action: String, userText: String): ToolInvocation? = when (action) {
        "list" -> ToolInvocation(
            name = "memory_list",
            group = ToolGroup.READ,
            summary = "查看长期记忆",
            confirmation = "允许查看长期记忆吗？",
        ) { _, _ ->
            val items = tools.memory.all()
            if (items.isEmpty()) {
                ToolResult.success("还没有保存长期记忆。")
            } else {
                ToolResult.success(
                    tools.memory.formatList(items),
                    "你让我记住 ${items.size} 条内容。",
                )
            }
        }
        "search" -> ToolInvocation(
            name = "memory_search",
            group = ToolGroup.READ,
            summary = "搜索长期记忆",
            confirmation = "允许搜索长期记忆吗？",
        ) { _, _ ->
            val items = tools.memory.search(userText, 5)
            if (items.isEmpty()) {
                ToolResult.success("我没有找到相关长期记忆。")
            } else {
                ToolResult.success(
                    tools.memory.formatList(items),
                    "我找到 ${items.size} 条相关记忆：${items.joinToString("；") { it.content }.take(300)}",
                )
            }
        }
        else -> null
    }

    // ── files ────────────────────────────────────────────────────────
    private fun fileRead(args: JSONObject): ToolInvocation? {
        val path = args.optString("path").trim()
        if (path.isBlank()) return null
        return ToolInvocation(
            name = "read_file",
            group = ToolGroup.READ,
            summary = "读取 $path",
            confirmation = "允许读取这个文件吗？",
        ) { _, _ -> tools.files.readFile(path) }
    }

    private fun fileWrite(args: JSONObject): ToolInvocation? {
        val path = args.optString("path").trim()
        val content = args.optString("content")
        if (path.isBlank() || content.isBlank()) return null
        return ToolInvocation(
            name = "write_file",
            group = ToolGroup.WRITE,
            summary = "写入 $path",
            confirmation = "确认把内容写入「$path」吗？同名文件会被覆盖。",
        ) { _, _ -> tools.files.writeFile(path, content, overwrite = true) }
    }

    private fun screenshot(): ToolInvocation? = ToolInvocation(
        name = "take_screenshot",
        group = ToolGroup.READ,
        summary = "截屏",
        confirmation = "允许截取当前屏幕吗？截图只保存在本机。",
    ) { _, _ -> tools.files.takeScreenshot() }
}
