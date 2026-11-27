package com.pilot.agent.assistant.exec

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import com.pilot.agent.action.ActionCatalog
import com.pilot.agent.action.AppLaunchIndex
import com.pilot.agent.action.DeviceActions
import com.pilot.agent.action.ReminderManager
import com.pilot.agent.action.SystemOps
import com.pilot.agent.assistant.routing.RouteDecision
import com.pilot.agent.music.MusicPlayer
import com.pilot.agent.tool.LaunchResults
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
            "media" -> media(decision.action, args, userText)
            "reminder" -> reminder(decision.action, args, userText)
            "scheduled_task" -> scheduledTask(decision.action, args, userText)
            "memory_write" -> memoryWrite(args)
            "memory_get" -> memoryGet(decision.action, userText)
            "memory_forget" -> memoryForget(args)
            "file_read" -> fileRead(args)
            "file_write" -> fileWrite(args, userText)
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
        // app 槽是用户已经明确说「打开 X」之后抽出来的目标名，
        // 这里再要求句子里带意图动词就多余了（槽本身就是命令对象）。
        val catalog = ActionCatalog.resolveTarget(app)
        val entry = if (catalog == null) appIndexProvider()?.resolve(app) else null
        if (catalog == null && entry == null) return null

        val summary = "打开 $app"
        return ToolInvocation(
            name = "open_app",
            group = ToolGroup.READ,
            summary = summary,
            confirmation = "允许打开「$app」吗？",
        ) { ctx, _ ->
            // ★ 所有启动都经 [VerifiedLauncher] 回查（LaunchResults）：HarmonyOS/EMUI 后台拦截
            //   startActivity 不抛异常，旧的 `runCatching { startActivity }.isSuccess` 会谎报「已打开」。
            // 1) 系统设置页 / 系统动作 / deep link。
            if (catalog != null) {
                val intent = ActionCatalog.toIntent(catalog)
                return@ToolInvocation LaunchResults.launch(
                    ctx,
                    intent,
                    label = catalog.label,
                    okMessage = "已打开「${catalog.label}」",
                    okSpoken = "已打开${catalog.label}",
                    timeoutMs = 6_000L,
                )
            }
            // 2) 普通 App：交给 DeviceActions.openApp —— 它内置「悬浮豁免 → 回桌面重试 →
            //    桌面图标手势 → 系统搜索」的降级链。HarmonyOS 冷启动设置主页这类受保护页时，
            //    只有由桌面（系统应用）拉起才放行；launchComponent 没有这条兜底，会直接失败。
            val launchable = entry ?: return@ToolInvocation ToolResult.error("没找到「$app」")
            val actions = actionsProvider()
            if (actions != null) {
                actions.openApp(launchable.pkg)
                val outcome = actions.lastLaunchOutcome
                return@ToolInvocation if (outcome != null) {
                    LaunchResults.toResult(
                        outcome,
                        launchable.appLabel,
                        "已打开「${launchable.appLabel}」",
                        "已打开${launchable.appLabel}",
                    )
                } else {
                    ToolResult.error("没能打开「${launchable.appLabel}」。")
                }
            }
            // 3) 没有无障碍服务：退回包名主入口（同样回查，不谎报）。
            val launch = ctx.packageManager.getLaunchIntentForPackage(launchable.pkg)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ?: return@ToolInvocation ToolResult.error("没找到「$app」的主入口。")
            LaunchResults.launch(
                ctx,
                launch,
                label = launchable.appLabel,
                okMessage = "已打开「${launchable.appLabel}」",
                okSpoken = "已打开${launchable.appLabel}",
                expectPackage = launchable.pkg,
                timeoutMs = 6_000L,
            )
        }
    }

    // ── volume ───────────────────────────────────────────────────────
    private fun volume(action: String, args: JSONObject): ToolInvocation? {
        val ops = SystemOps(context)
        // SlotExtractor 已抽出「铃声 / 来电」→ stream=ring。这里必须消费它，
        // 否则「把铃声调到 50%」实际改的是媒体音量。
        val stream = when (args.optString("stream")) {
            "ring" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            "notification" -> AudioManager.STREAM_NOTIFICATION
            "system" -> AudioManager.STREAM_SYSTEM
            else -> AudioManager.STREAM_MUSIC
        }
        val label = if (stream == AudioManager.STREAM_MUSIC) "音量" else "铃声"
        return when (action) {
            "set" -> {
                val percent = args.optInt("percent", -1)
                if (percent < 0) return null
                ToolInvocation(
                    name = "set_volume",
                    group = ToolGroup.READ,
                    summary = "${label}设为 $percent%",
                    confirmation = "允许把${label}设为 $percent% 吗？",
                ) { _, _ ->
                    val message = ops.setVolume(percent, stream, label)
                    if (message == null) ToolResult.error("${label}没有调成功。")
                    else ToolResult.success(message)
                }
            }
            "adjust" -> {
                val delta = args.optInt("percent", 0)
                if (delta == 0) return null
                ToolInvocation(
                    name = "adjust_volume",
                    group = ToolGroup.READ,
                    summary = if (delta > 0) "${label}调大 $delta%" else "${label}调小 ${-delta}%",
                    confirmation = "允许调整${label}吗？",
                ) { _, _ ->
                    val message = ops.adjustVolume(delta, stream, label)
                    if (message == null) ToolResult.error("${label}没有调成功。")
                    else ToolResult.success(message)
                }
            }
            "status" -> ToolInvocation(
                name = "volume_status",
                group = ToolGroup.READ,
                summary = "查看${label}",
                confirmation = "允许查看当前${label}吗？",
            ) { _, _ ->
                val current = ops.currentVolumePercent(stream)
                if (current == null) ToolResult.unavailable("读不到当前${label}。")
                else ToolResult.success("当前${label} $current%")
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
    private fun media(action: String, args: JSONObject, userText: String): ToolInvocation? {
        // 播放：直接走 Netease 检索 + MusicPlayer，一步到位。
        // 之前这里 return null 交回 AgentLoop，结果云端规划器去点开屏广告、
        // 翻应用抽屉，3-6 步还经常失败（真机实测）。
        if (action == "play") return musicPlay(args, userText)
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

    /**
     * 点歌：消费抽取层已经判定好的 kind/query/artist/chart，不再 contain() 猜意图。
     * 顺序：本地文件 → 网易云检索（歌名/歌手/流派/场景/榜单）→ 直接播放。
     */
    private fun musicPlay(args: JSONObject, userText: String): ToolInvocation {
        val kind = args.optString("kind").ifBlank { "song" }
        val query = args.optString("query").trim()
        val artist = args.optString("artist").trim()
        val chart = args.optString("chart").trim()
        val label = when {
            query.isNotBlank() -> query
            artist.isNotBlank() -> artist
            chart.isNotBlank() -> chart
            else -> userText.take(20)
        }
        return ToolInvocation(
            name = "music_play",
            group = ToolGroup.READ,
            summary = "播放 ${label}",
            confirmation = "允许播放音乐吗？",
        ) { ctx, _ ->
            val settings = com.pilot.agent.agent.AgentSettings.load()
            val base = settings.musicBaseUrl
            val cookie = settings.neteaseCookie

            // 1) 具体歌名/歌手：先查本地，命中就零网络播放
            val searchKey = when {
                query.isNotBlank() && artist.isNotBlank() -> "$artist $query"
                query.isNotBlank() -> query
                artist.isNotBlank() -> artist
                else -> ""
            }
            if (searchKey.isNotBlank() && kind != "genre" && kind != "scene") {
                val local = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.pilot.agent.music.LocalMusicLibrary.find(ctx, searchKey)
                }
                if (local != null) {
                    val started = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                        MusicPlayer.get(ctx).playQueue(
                            listOf(
                                MusicPlayer.Track(
                                    title = local.title,
                                    artist = local.artist,
                                    source = MusicPlayer.Source.Local(local.uri),
                                ),
                            ),
                        )
                    }
                    return@ToolInvocation if (started) {
                        ToolResult.success("正在播放本地音乐：${local.display}")
                    } else {
                        ToolResult.error("本地音乐播放失败：${local.display}")
                    }
                }
            }

            // 2) 网易云检索
            val songs = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                when (kind) {
                    "chart" -> chartSongs(base, chart, cookie)
                    "daily" -> com.pilot.agent.music.NeteaseApi.everydayRecommendations(base, cookie, 20)
                    "liked" -> com.pilot.agent.music.NeteaseApi.likedSongs(base, cookie, 20)
                    "recent" -> com.pilot.agent.music.NeteaseApi.recentSongs(base, cookie, 20)
                    "none" -> com.pilot.agent.music.MusicHistoryStore.recent(20)
                        .ifEmpty { com.pilot.agent.music.NeteaseApi.chartSongs(base, 3778678L, cookie, 20) }
                    else -> com.pilot.agent.music.NeteaseApi.search(base, searchKey.ifBlank { "热门" }, 10, cookie)
                }
            }
            if (songs.isEmpty()) {
                return@ToolInvocation ToolResult.unavailable(
                    "没搜到「${label}」。检查音乐服务是否在线、或先在 App 里登录网易云。",
                )
            }
            com.pilot.agent.music.MusicHistoryStore.remember(songs)
            val tracks = songs.map { song ->
                MusicPlayer.Track(
                    title = song.name,
                    artist = song.artist,
                    resolver = {
                        com.pilot.agent.music.NeteaseApi.playUrl(base, song.id, cookie)
                            ?.let { MusicPlayer.Source.Remote(it) }
                    },
                    lyricProvider = {
                        com.pilot.agent.music.NeteaseApi.lyrics(base, song.id, cookie)
                    },
                )
            }
            val started = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                MusicPlayer.get(ctx).playQueue(tracks)
            }
            if (started) {
                ToolResult.success("正在播放：${tracks.first().display}，共 ${tracks.size} 首")
            } else {
                ToolResult.error("播放器启动失败，可能是音源地址无效。")
            }
        }
    }

    /** 榜单名 → 网易云官方歌单 id。 */
    private suspend fun chartSongs(
        base: String,
        chart: String,
        cookie: String,
    ): List<com.pilot.agent.music.NeteaseApi.Song> {
        val id = when (chart) {
            "new" -> 3779629L
            "rising" -> 19723756L
            "hot" -> 3778678L
            else -> 3778678L
        }
        return com.pilot.agent.music.NeteaseApi.chartSongs(base, id, cookie, 20)
    }

    // ── reminder ─────────────────────────────────────────────────────
    /**
     * 提醒管理器只提供一个入口 [ReminderManager.handle]：它自己认「提醒我 /
     * 我的提醒 / 取消提醒」并解析时间与标题。这里只负责把请求交给它。
     *
     * 取消是破坏性操作 → 走 DELETE 组（默认每次询问），不当作「用户已经明确要求」。
     */
    private fun reminder(action: String, args: JSONObject, userText: String): ToolInvocation? = when (action) {
        "schedule" -> ToolInvocation(
            name = "create_reminder",
            group = ToolGroup.WRITE,
            summary = "创建提醒",
            confirmation = "允许创建这个提醒吗？",
            explicitAuthorized = true,
        ) { ctx, _ ->
            // P1：Router 用 ChineseTimeParser 判定"有参数可执行"，执行器必须消费
            // 同一套结构化结果，不能再交给只支持"N分钟后"的 parse() 重解析。
            val atIso = args.optString("at")
            val afterSec = args.optLong("after", 0L)
            val structuredAt = when {
                atIso.isNotBlank() -> runCatching {
                    java.time.LocalDateTime.parse(atIso).atZone(java.time.ZoneId.systemDefault())
                        .toInstant().toEpochMilli()
                }.getOrNull()
                afterSec > 0L -> System.currentTimeMillis() + afterSec * 1000L
                else -> null
            }
            if (structuredAt == null) {
                // Router 放行但槽位不可解析：不能把"无法确定时间"包装成 SUCCESS。
                return@ToolInvocation ToolResult.unavailable("这个提醒的时间没解析出来，请换个说法，例如“30 分钟后提醒我”。")
            }
            val title = args.optString("title").ifBlank {
                userText.replace(Regex("""提醒我|记得提醒|^(请|帮我)"""), "").trim()
                    .ifBlank { "提醒" }
            }
            val message = ReminderManager.createFromParsed(ctx, title, structuredAt)
                ?: return@ToolInvocation ToolResult.unavailable("提醒参数不完整，请重新说一次。")
            if (message.startsWith("已设提醒")) ToolResult.success(message)
            else ToolResult.error(message)
        }
        "list" -> ToolInvocation(
            name = "list_reminders",
            group = ToolGroup.READ,
            summary = "查看提醒",
            confirmation = "允许查看提醒吗？",
        ) { ctx, _ -> ToolResult.success(ReminderManager.listText(ctx)) }
        "cancel" -> ToolInvocation(
            name = "cancel_reminder",
            group = ToolGroup.DELETE,
            summary = "取消提醒",
            confirmation = "确认取消这个提醒吗？",
        ) { ctx, _ ->
            // 有结构化 id 就按 id 精确取消；否则用原话里的关键词匹配标题。
            // 两者都空时 ReminderManager 会让用户先查看列表，绝不盲取消。
            val key = args.optString("id").ifBlank { userText }
            val message = ReminderManager.cancel(ctx, key)
            if (message.startsWith("已取消")) ToolResult.success(message)
            else ToolResult.unavailable(message)
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
                // Router 已抽出 at(ISO)/after(秒)/prompt —— 消费它们，
                // 不能再把原话丢给 ScheduledTaskManager.create 另起一套正则重解析。
                val atIso = args.optString("at")
                val afterSec = args.optLong("after", 0L)
                val at = when {
                    atIso.isNotBlank() -> runCatching {
                        java.time.LocalDateTime.parse(atIso)
                            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                    }.getOrNull()
                    afterSec > 0L -> System.currentTimeMillis() + afterSec * 1000L
                    else -> null
                }
                val prompt = args.optString("prompt").ifBlank {
                    args.optString("label").ifBlank {
                        userText.replace(Regex("""每天|定时|提醒我"""), "").trim()
                    }
                }.trim()
                val repeat = args.optString("repeat")
                val daily = repeat == "DAILY"
                if (repeat == "WEEKDAYS" || repeat == "WEEKLY") {
                    return@ToolInvocation ToolResult.unavailable("定时任务目前只支持一次性或每天重复，工作日/每周还没开放。")
                }
                if (at == null || prompt.isBlank()) {
                    return@ToolInvocation ToolResult.unavailable(
                        "没听懂时间或要执行的内容。示例：每天 8 点查看财经新闻。",
                    )
                }
                val message = ScheduledTaskManager.createFromParsed(ctx, prompt, at, daily)
                    ?: return@ToolInvocation ToolResult.unavailable(
                        "定时任务参数不完整，请说清楚时间和要做的事，例如“每天 8 点查看财经新闻”。",
                    )
                if (message.startsWith("已创建")) ToolResult.success(message)
                else ToolResult.error(message)
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
                val id = args.optString("id").trim()
                val message = if (id.isNotBlank()) {
                    ScheduledTaskManager.cancelById(ctx, id) ?: "没有找到要取消的定时任务。"
                } else {
                    ScheduledTaskManager.cancel(ctx, args.optString("label").ifBlank { userText })
                }
                if (message.startsWith("已取消")) ToolResult.success(message)
                else ToolResult.unavailable(message)
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

    /**
     * 删记忆：用户明确说「忘掉…」才会走到这里，属于破坏性操作（DELETE 组）。
     * 结果必须报出删了几条、删了什么 —— 模糊匹配可能一次带走多条。
     */
    private fun memoryForget(args: JSONObject): ToolInvocation? {
        val keyword = args.optString("content").trim()
        if (keyword.isBlank()) return null
        return ToolInvocation(
            name = "memory_forget",
            group = ToolGroup.DELETE,
            summary = "删除长期记忆",
            confirmation = "要忘掉与「$keyword」相关的长期记忆吗？",
            explicitAuthorized = true,
        ) { _, _ ->
            val removed = tools.memory.forget(keyword)
            if (removed.isEmpty()) {
                ToolResult.success("没有找到与「$keyword」相关的长期记忆。")
            } else {
                ToolResult.success(
                    "已忘掉 ${removed.size} 条：${removed.joinToString("；") { it.content }.take(200)}",
                    "忘掉了 ${removed.size} 条相关记忆。",
                )
            }
        }
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

    private fun fileWrite(args: JSONObject, userText: String): ToolInvocation? {
        val path = args.optString("path").trim()
        val content = args.optString("content")
        if (path.isBlank() || content.isBlank()) return null
        // P1：只有用户明确说「覆盖」才允许覆盖同名文件。
        // 旧实现固定 overwrite=true，等于任何一次写入都可能悄悄替换掉已有文件。
        val overwrite = userText.contains("覆盖") || userText.contains("overwrite", ignoreCase = true)
        return ToolInvocation(
            name = "write_file",
            group = ToolGroup.WRITE,
            summary = "写入 $path",
            confirmation = if (overwrite) {
                "确认覆盖「$path」吗？"
            } else {
                "确认把内容写入「$path」吗？同名文件存在时会停止。"
            },
        ) { _, _ -> tools.files.writeFile(path, content, overwrite) }
    }

    private fun screenshot(): ToolInvocation? = ToolInvocation(
        name = "take_screenshot",
        group = ToolGroup.READ,
        summary = "截屏",
        confirmation = "允许截取当前屏幕吗？截图只保存在本机。",
    ) { _, _ -> tools.files.takeScreenshot() }
}
