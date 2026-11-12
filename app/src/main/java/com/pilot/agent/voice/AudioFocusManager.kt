package com.pilot.agent.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import com.pilot.agent.Dbg

/**
 * 音频焦点管理器 + 播报时的音乐让路策略。
 *
 * ## 现象 1：音乐播一会儿自动停
 * 根因：MiniMax 播报（也是 MediaPlayer）和音乐播放器**抢同一个流**。
 * 华为/EMUI 的音频策略比 AOSP 激进——后来的播放器会让前一个静音，
 * 甚至被系统直接停掉。于是「正在播放 晴天」这句播报一出口，歌就断了。
 *
 * ## 现象 2：播放中冒出一句「你说完了」
 * 根因：外放的歌词被麦克风录进去，ASR 误听成用户指令。
 * 这条由 [AudioGate] 解决（放音期间不送 ASR），与本类互补。
 *
 * ## 老板的要求（11-10）
 * 「ai 播报期间要自动停止音乐或者降低音乐音量」——两种策略都实现了：
 * - [MusicPolicy.DUCK]（默认）：音乐音量压到 15%，播完恢复。**不断歌**。
 * - [MusicPolicy.STOP]：直接停掉音乐，播完不自动恢复。
 *
 * ## 关键实现细节
 * 降音量**只动 MediaPlayer 自己的音量**（player.setVolume），
 * 不动系统 STREAM_MUSIC 音量——动系统流会覆盖用户的媒体音量设置，
 * 而且恢复时容易把音乐永久静音（先静音再记录音量，记录到的是已经
 * 静音后的 0，恢复成 0，歌彻底哑了）。这是踩过的坑。
 */
object AudioFocusManager {

    private const val TAG = "PilotAudioFocus"

    /** 播报时音乐的处理策略。 */
    enum class MusicPolicy { DUCK, STOP }

    /** 当前策略（由设置项驱动，默认 DUCK）。 */
    @Volatile
    var policy: MusicPolicy = MusicPolicy.DUCK

    /** 音乐播放器注册：设置音量（0~1）。 */
    @Volatile
    var musicVolume: ((Float) -> Unit)? = null

    /** 音乐播放器注册：停止播放。 */
    @Volatile
    var musicStop: (() -> Unit)? = null

    /** 音乐播放器注册：暂停并返回本次是否真的从播放态切到暂停态。 */
    @Volatile
    var musicPause: (() -> Boolean)? = null

    /** 音乐播放器注册：恢复播放。 */
    @Volatile
    var musicResume: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var musicPausedForVoice = false

    private var musicRequest: AudioFocusRequest? = null
    private var musicManager: AudioManager? = null

    /**
     * 音乐开始播放前申请焦点。
     * 拿到焦点意味着：别的 App（网易云、系统音乐）出声时，音乐会被系统让路。
     */
    fun requestMusicFocus(context: Context): Boolean {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        musicManager = am
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { change ->
                Dbg.i(TAG, "音乐焦点变化 $change")
                when (change) {
                    // 系统要短暂占用（来电）→ 让路但不毁掉播放状态
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                        Dbg.i(TAG, "音乐暂让（duck）")
                        musicVolume?.invoke(0.0f)
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        Dbg.i(TAG, "音乐暂停（transient loss）")
                        musicVolume?.invoke(0.0f)
                    }
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        Dbg.i(TAG, "音乐恢复")
                        musicVolume?.invoke(1.0f)
                    }
                }
            }
            .build()
        musicRequest = req
        val res = am.requestAudioFocus(req)
        val ok = res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        Dbg.i(TAG, "音乐焦点申请 $res granted=$ok")
        return ok
    }

    fun abandonMusicFocus() {
        musicPausedForVoice = false
        val req = musicRequest ?: return
        musicRequest = null
        runCatching { musicManager?.abandonAudioFocusRequest(req) }
        Dbg.i(TAG, "音乐焦点已释放")
    }

    /**
     * 唤醒词命中或语音打断时调用。暂停只在主线程执行，避免从 AudioRecord
     * 线程直接操作线程受限的 ExoPlayer。
     */
    fun pauseMusicForVoice() {
        main.post {
            if (musicPausedForVoice) return@post
            val pause = musicPause ?: return@post
            if (pause()) {
                musicPausedForVoice = true
                Dbg.i(TAG, "音乐已为语音输入暂停")
            }
        }
    }

    /**
     * 这一轮语音结束后恢复音乐。若 AI 正在播报，则等 TTS 和麦克风闸门
     * 都释放后再恢复，避免在播报声里突然把歌曲抬起来。
     */
    fun resumeMusicAfterVoice() {
        main.post {
            if (!musicPausedForVoice) return@post
            AudioGate.whenUnmuted {
                main.post {
                    if (!musicPausedForVoice) return@post
                    musicPausedForVoice = false
                    musicResume?.invoke()
                    Dbg.i(TAG, "语音输入结束，音乐恢复")
                }
            }
        }
    }

    /**
     * 播报前临时抢焦点（让音乐让路）。
     *
     * 抢 TRANSIENT_MAY_DUCK 焦点时系统会把音乐压低，但华为的策略
     * 不一定压得够狠，所以自己再显式处理一次（[applyPolicy]），
     * 保证「播报期间音乐一定让路」是确定行为而不是看系统心情。
     */
    fun beginSpeech(context: Context): AutoCloseable {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        // 策略跟着设置走（每次播报时读，切换设置立即生效）
        policy = if (com.pilot.agent.agent.AgentSettings.load().musicPolicy ==
            com.pilot.agent.agent.AgentSettings.MUSIC_STOP
        ) MusicPolicy.STOP else MusicPolicy.DUCK
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { }
            .build()
        val res = am.requestAudioFocus(req)
        markSpoke()
        Dbg.i(TAG, "播报焦点 $res，策略=$policy")
        applyPolicy()
        return AutoCloseable {
            runCatching { am.abandonAudioFocusRequest(req) }
            // 播完恢复音乐音量。
            // STOP 策略也恢复——「音乐刚开始但播报刚抢过」那条路径只是**暂时**
            // 压低，等播报说完就该正常放；只有「真的被 stop 掉了」才不恢复
            // （那种情况播放器已经释放，setVolume 也无处可去）。
            musicVolume?.invoke(1.0f)
            Dbg.i(TAG, "播报焦点已释放")
        }
    }

    /** 按策略让音乐让路。 */
    private fun applyPolicy() {
        when (policy) {
            MusicPolicy.DUCK -> {
                musicVolume?.invoke(0.15f)
                Dbg.i(TAG, "音乐降到 15%（不断歌）")
            }
            MusicPolicy.STOP -> {
                musicStop?.invoke()
                Dbg.i(TAG, "音乐已停止（播报优先）")
            }
        }
    }

    /**
     * 刚才有没有播报抢占过。
     *
     * 场景：「播放晴天」这条指令本身会先播报一句「正在播放晴天」，
     * 然后音乐才开始。STOP 策略下播报时把音乐停了——但那时音乐还没起，
     * 停了个寂寞，音乐随后照常开始，等于策略没生效。
     *
     * 判定：播报结束后一小段时间内（[justSpokeWindowMs]）认为
     * 「播报刚抢过」，此时开始的音乐应该**接着被压低或直接不播**。
     */
    @Volatile
    private var lastSpeechAt = 0L

    private const val justSpokeWindowMs = 3000L

    fun markSpoke() {
        lastSpeechAt = System.currentTimeMillis()
    }

    /** 音乐刚开始时问一下：现在是不是还在播报的余波里？ */
    fun justSpoke(): Boolean =
        System.currentTimeMillis() - lastSpeechAt < justSpokeWindowMs

    /**
     * 音乐刚开始时的处理：STOP 策略且播报刚抢过 → 压低音量等播报结束。
     * @return true = 音乐应该继续播（只是暂时小声）
     */
    fun onMusicStarted(): Boolean {
        if (policy == MusicPolicy.STOP && justSpoke()) {
            Dbg.i(TAG, "音乐刚开始但播报刚抢过（STOP 策略）→ 压低音量")
            musicVolume?.invoke(0.15f)
            return false
        }
        return true
    }
}
