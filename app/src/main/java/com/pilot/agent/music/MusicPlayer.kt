package com.pilot.agent.music

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import com.pilot.agent.Dbg

/**
 * 在线音乐播放（MediaPlayer 直接拉 HTTP 流）。
 *
 * 不依赖网易云 App 在前台，也不走它的 UI——这是自建 API 代理换来的能力：
 * 「播放周杰伦的晴天」一句话，歌就开始放了。
 */
class MusicPlayer private constructor(private val context: Context) {

    private var player: MediaPlayer? = null
    private var currentTitle: String? = null
    /** 放音期间占住麦克风闸门（防 ASR 把歌误听成「你说完了」） */
    private var audioGate: AutoCloseable? = null

    @Volatile
    var isPlaying: Boolean = false
        private set

    fun play(url: String, title: String, onDone: () -> Unit = {}, onError: (String) -> Unit = {}): Boolean {
        stop()
        return runCatching {
            val mp = MediaPlayer()
            player = mp
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            mp.setDataSource(url)
            mp.setOnPreparedListener {
                it.start()
                isPlaying = true
                currentTitle = title
                // 新播放器一律满音量起步：上一首可能停在 15%（被播报压低过），
                // 不复位的话新歌一开口就是小声的。
                runCatching { it.setVolume(1f, 1f) }
                // ★ 放音一开始就占闸：麦克风录到的是歌不是人声，
                //   不占闸的话 ASR 会把歌词听成「你说完了」并播报打断音乐。
                audioGate = com.pilot.agent.voice.AudioGate.hold("music")
                // ★ 申请音频焦点：没有焦点的话，播报(MiniMax 也是 MediaPlayer)
                //   一出声系统就把音乐停掉——「播放一会自动就停了」的根因。
                com.pilot.agent.voice.AudioFocusManager.requestMusicFocus(context)
                // ★ 注册让路回调：播报期间要么降音量要么停歌（老板要求）
                com.pilot.agent.voice.AudioFocusManager.musicVolume = { v ->
                    runCatching { mp.setVolume(v, v) }
                }
                com.pilot.agent.voice.AudioFocusManager.musicStop = { stop() }
                // 若播报刚抢过（STOP 策略：指令播报先于音乐开始），先压低
                com.pilot.agent.voice.AudioFocusManager.onMusicStarted()
                Dbg.i(TAG, "开始播放：$title")
            }
            mp.setOnCompletionListener {
                isPlaying = false
                stop()
                onDone()
            }
            mp.setOnErrorListener { _, what, extra ->
                Dbg.w(TAG, "MediaPlayer 错误 what=$what extra=$extra")
                isPlaying = false
                stop()
                onError("播放失败（$what/$extra）")
                true
            }
            mp.prepareAsync()
            true
        }.getOrElse {
            Dbg.e(TAG, "播放启动失败：${it.message}")
            onError("播放启动失败：${it.message}")
            false
        }
    }

    fun stop() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        isPlaying = false
        currentTitle = null
        // 释放闸门：麦克风恢复可信，唤醒词能重新被识别
        runCatching { audioGate?.close() }
        audioGate = null
        // 释放焦点：别的 App（网易云等）能正常出声
        com.pilot.agent.voice.AudioFocusManager.abandonMusicFocus()
        // 注销让路回调（别让已停止的播放器还挂着引用）
        com.pilot.agent.voice.AudioFocusManager.musicVolume = null
        com.pilot.agent.voice.AudioFocusManager.musicStop = null
    }

    fun nowPlaying(): String? = if (isPlaying) currentTitle else null

    companion object {
        private const val TAG = "PilotMusic"

        @Volatile
        private var instance: MusicPlayer? = null

        fun get(ctx: Context): MusicPlayer = instance ?: synchronized(this) {
            instance ?: MusicPlayer(ctx.applicationContext).also { instance = it }
        }
    }
}
