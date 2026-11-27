package com.pilot.agent.action

import android.content.Context
import com.pilot.agent.agent.AgentSettings
import com.pilot.agent.music.MusicPlayer
import com.pilot.agent.music.NeteaseApi
import com.pilot.agent.query.QueryEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 无屏幕本机执行的结果（m-20261127-106）。
 *
 * UI 与后台/语音/定时/调试通道共用这一套执行器；**要不要屏幕的判定**仍在
 * [ScreenRequirements]/[ScreenRequirements.route]，这里只负责真正去做。
 */
sealed interface ScreenlessOutcome {
    /** 已经完成或有明确回执，直接上屏/播报。 */
    data class Done(val message: String) : ScreenlessOutcome

    /** 缺权限/没有能力：文案已经问清「要不要带路」，调用方决定是否接着引导。 */
    data class NeedsGuide(val target: CommandTarget, val message: String) : ScreenlessOutcome
}

/**
 * 不需要屏幕的本地能力：日程 / 提醒 / 待办 / 查询 / 音乐 / B 类系统命令。
 *
 * @see ScreenRequirements 判定
 */
object ScreenlessTasks {

    /** 同步判断本机能不能接这句话（用于分流时决定是否接管）。 */
    fun canRun(goal: String): Boolean {
        val g = goal.trim()
        if (g.isEmpty()) return false
        if (CalendarTool.handlesCancel(g) || CalendarTool.handles(g)) return true
        if (ReminderManager.handles(g)) return true
        if (PersonalTodoStore.handles(g)) return true
        if (QueryEngine.canHandle(g)) return true
        if (ScreenRequirements.isMusicCommand(g)) return true
        return CommandSemantics.classify(g)?.kind == CommandKind.RESULT
    }

    /** @return null = 本机没法接（交给原链路/Agent）。 */
    suspend fun run(context: Context, goal: String): ScreenlessOutcome? {
        val g = goal.trim()
        if (g.isEmpty()) return null

        if (CalendarTool.handlesCancel(g)) return ScreenlessOutcome.Done(CalendarTool.cancelLastCreated(context))
        if (CalendarTool.handles(g)) return ScreenlessOutcome.Done(CalendarTool.create(context, g))
        if (ReminderManager.handles(g)) return ScreenlessOutcome.Done(ReminderManager.handle(context, g))
        if (PersonalTodoStore.handles(g)) return ScreenlessOutcome.Done(PersonalTodoStore.handle(g))

        if (QueryEngine.canHandle(g)) {
            val answer = QueryEngine.handle(g)
                ?: "没能查到「$g」的结果，可能是网络不可用或暂时没有相关内容。"
            return ScreenlessOutcome.Done(answer)
        }

        if (ScreenRequirements.isMusicCommand(g)) return ScreenlessOutcome.Done(playMusic(context, g))

        val cmd = CommandSemantics.classify(g)
        if (cmd != null && cmd.kind == CommandKind.RESULT) {
            return when (val r = SystemCommandRunner.run(SystemOps(context), cmd, g)) {
                null -> null
                is SystemRunOutcome.Done -> ScreenlessOutcome.Done(r.message)
                is SystemRunOutcome.NeedsGuide -> ScreenlessOutcome.NeedsGuide(r.target, r.message)
            }
        }
        return null
    }

    /** 直接播放：搜索/热歌 → 拉起播放器（不需要读屏）。 */
    private suspend fun playMusic(context: Context, goal: String): String {
        val s = AgentSettings.load()
        val query = goal
            .replace(Regex("^(帮我|给我|请)?(播放|放首|放一下|来首|来一首|放歌|听歌)"), "")
            .replace(Regex("(这首歌|的歌|歌曲|音乐|吧|呗)$"), "")
            .trim()
        val songs = withContext(Dispatchers.IO) {
            if (query.isBlank()) {
                NeteaseApi.chartSongs(s.musicBaseUrl, 3778678L, s.neteaseCookie, 20)
            } else {
                NeteaseApi.search(s.musicBaseUrl, query, 5, s.neteaseCookie)
            }
        }
        if (songs.isEmpty()) return "没搜到「${query.ifBlank { "热门" }}」，检查音乐服务或网络后再说一次。"
        val tracks = songs.map { song ->
            MusicPlayer.Track(
                title = song.name,
                artist = song.artist,
                resolver = {
                    NeteaseApi.playUrl(s.musicBaseUrl, song.id, s.neteaseCookie)
                        ?.let { MusicPlayer.Source.Remote(it) }
                },
                lyricProvider = {
                    NeteaseApi.lyrics(s.musicBaseUrl, song.id, s.neteaseCookie)
                },
            )
        }
        val started = withContext(Dispatchers.Main.immediate) {
            MusicPlayer.get(context).playQueue(tracks)
        }
        return if (started) "正在播放：${songs.first().display}" else "音乐播放失败，检查音乐服务后再说一次。"
    }
}
