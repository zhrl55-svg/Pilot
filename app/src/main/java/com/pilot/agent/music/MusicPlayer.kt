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
