package com.pilot.agent.voice

import android.content.Context
import com.pilot.agent.Dbg
import com.pilot.agent.agent.AgentSettings

/**
 * 播报路由：按设置选系统 TTS 还是 MiniMax。
 *
 * 引擎按需创建——SystemTts 构造时就会拉起系统 TTS 引擎（有开销），
 * 只用 MiniMax 的用户不该付这个成本，反之亦然。
 */
class Speaker(context: Context) {

    private val appContext = context.applicationContext

    private var system: SystemTts? = null
    private var minimax: MiniMaxTts? = null

    @Volatile
    var lastError: String? = null
        private set

    fun speak(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return

        val s = AgentSettings.load()
        val engine: SpeechOutput = when (s.ttsEngine) {
            AgentSettings.TTS_OFF -> return
            AgentSettings.TTS_MINIMAX -> {
                if (s.apiKey.isBlank()) {
                    lastError = "MiniMax 播报需要 API Key"
                    Dbg.w(TAG, lastError!!)
                    return
                }
                minimax ?: MiniMaxTts(appContext).also { minimax = it }
            }
            else -> system ?: SystemTts(appContext).also { system = it }
        }

        // 播报只说重点。UI 上的日志可以很长，念出来超过 10 秒就没人听了。
        val spoken = if (t.length > 60) t.take(60) + "……" else t

        val fallbackToSystem = {
            // 云端哑了不能一声不吭——联网失败、额度用尽、服务异常都走这里
            val sys = system ?: SystemTts(appContext).also { system = it }
            sys.speak(spoken, onDone = {}, onError = { m2 -> Dbg.w(TAG, "系统 TTS 兜底也失败：$m2") })
        }

        engine.speak(
            spoken,
            onDone = {},
            onError = { msg ->
                lastError = msg
                Dbg.w(TAG, "播报失败：$msg")
                if (s.ttsEngine == AgentSettings.TTS_MINIMAX) {
                    Dbg.i(TAG, "云端播报失败，降级系统 TTS")
                    fallbackToSystem()
                }
            },
        )
    }

    fun stop() {
        system?.stop()
        minimax?.stop()
    }

    fun release() {
        system?.release()
        minimax?.release()
        system = null
        minimax = null
    }

    /** 设置页展示用 */
    fun statusText(): String = when (AgentSettings.load().ttsEngine) {
        AgentSettings.TTS_OFF -> "已关闭"
        AgentSettings.TTS_MINIMAX -> minimax?.lastError?.let { "MiniMax 异常" } ?: "MiniMax 云端"
        else -> system?.lastError?.let { "系统 TTS 异常" } ?: "系统 TTS"
    }

    private companion object {
        const val TAG = "PilotTts"
    }
}
