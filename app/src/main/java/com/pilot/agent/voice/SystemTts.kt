package com.pilot.agent.voice

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.pilot.agent.Dbg
import com.pilot.agent.agent.AgentSettings
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 系统 TTS。
 *
 * 优点：零延迟、离线、不花钱。agent 的播报都是「已打开设置」这种短句，够用。
 * 缺点：音色取决于系统装的引擎（华为是 hiai，小米是自家引擎），质量参差。
 *
 * 初始化是异步的（onInit 回调），所以用 latch 等它完成，别在没就绪时 speak——
 * 那会静默失败，表现为「agent 干完活一声不吭」。
 */
/** 系统 TTS 的一个音色。 */
data class SystemVoice(
    val name: String,
    val needsNetwork: Boolean,
    val quality: Int,
    /** locale，如 zh-CN / zh-TW */
    val locale: String = "",
    /** 引擎自定义特性串（华为引擎靠它区分不同音色） */
    val features: String = "",
) {
    /**
     * 显示用的标签。
     *
     * ★ 华为引擎（com.huawei.hiai）所有中文音色的 name 都是 "zh"，
     *   光看名字分不出区别。只能靠 locale / features / 序号来标。
     */
    fun label(index: Int): String = buildString {
        append("音色 ").append(index + 1)
        if (locale.isNotBlank() && locale != "zh") {
            append(" (").append(locale).append(")")
        }
        if (features.isNotBlank() && features != "[]") {
            append(" ").append(features)
        }
        if (needsNetwork) append("·联网")
    }
}

class SystemTts(context: Context) : SpeechOutput {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val initLatch = CountDownLatch(1)

    @Volatile
    private var tts: TextToSpeech? = null

    @Volatile
    private var ready = false

    @Volatile
    private var langOk = false

    @Volatile
    var lastError: String? = null
        private set

    private val seq = AtomicInteger(0)

    /**
     * 一条播报的终止回调，保证 onDone / onError 恰好触发其一、且只触发一次（V-04）。
     */
    private class Pending(
        private val onDone: () -> Unit,
        private val onError: (String) -> Unit,
    ) {
        private val fired = AtomicBoolean(false)

        fun done() {
            if (fired.compareAndSet(false, true)) runCatching(onDone)
        }

        fun error(msg: String) {
            if (fired.compareAndSet(false, true)) runCatching { onError(msg) }
        }
    }

    /**
     * 在途播报（utteranceId → 回调）。
     *
     * 以前是**静态** Map，QUEUE_FLUSH 冲掉的旧条目一直留在里面，直到下次 stop() 才被
     * 统一触发，误调 onSpeechDone 自动开麦（V-02）。现在实例级，并在冲掉/打断时立即收尾。
     */
    private val pending = java.util.concurrent.ConcurrentHashMap<String, Pending>()

    /**
     * stop() 代数。speak 要先等初始化、再 post 到主线程，期间若被 stop()，
     * 这条不能再出声（以前会"打断了它还接着播"），直接按被打断收尾。
     */
    @Volatile
    private var stopGen = 0

    init {
        try {
            tts = TextToSpeech(appContext) { status ->
                ready = status == TextToSpeech.SUCCESS
                if (ready) {
                    val r = tts?.setLanguage(Locale.SIMPLIFIED_CHINESE)
                    langOk = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
                    if (!langOk) {
                        // ★ 缺中文语音包时再试一次默认 locale——
                        //   有些 ROM（华为智慧语音）默认是英文，中文包要单独下，
                        //   但 setLanguage 失败不代表完全不能出声。
                        val r2 = tts?.setLanguage(Locale.getDefault())
                        val r3 = tts?.setLanguage(Locale.SIMPLIFIED_CHINESE)
                        langOk = r2 != TextToSpeech.LANG_MISSING_DATA || r3 != TextToSpeech.LANG_MISSING_DATA
                        if (!langOk) {
                            lastError = "系统 TTS 没有中文语音包"
                            Dbg.w(TAG, lastError!!)
                        }
                    }
                    // 语速与音色都来自设置；设置页改完不用重启 App，下一次播报生效。
                    val s = AgentSettings.load()
                    tts?.setSpeechRate(s.ttsRate)
                    // 应用用户选择的音色（可能为空 = 默认）
                    applyVoice(s.systemTtsVoice)
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) = Unit
                        override fun onDone(utteranceId: String?) {
                            utteranceId?.let { pending.remove(it)?.done() }
                        }

                        /** 被 stop() 或后一条 QUEUE_FLUSH 冲掉：按"播完"收尾，立刻释放闸门。 */
                        override fun onStop(utteranceId: String?, interrupted: Boolean) {
                            utteranceId?.let { pending.remove(it)?.done() }
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            utteranceId?.let { pending.remove(it)?.error("系统 TTS 播报出错") }
                        }

                        override fun onError(utteranceId: String?, errorCode: Int) {
                            Dbg.w(TAG, "系统 TTS 播报出错 code=$errorCode")
                            utteranceId?.let { pending.remove(it)?.error("系统 TTS 播报出错（code=$errorCode）") }
                        }
                    })
                    Dbg.i(TAG, "系统 TTS 就绪 引擎=${engineName()} lang=${if (langOk) "zh" else "缺中文包"}")
                } else {
                    lastError = "系统 TTS 初始化失败 status=$status"
                    Dbg.e(TAG, lastError!!)
                }
                initLatch.countDown()
            }
        } catch (t: Throwable) {
            lastError = "${t.javaClass.simpleName}: ${t.message}"
            Dbg.e(TAG, "系统 TTS 创建失败 $lastError")
            initLatch.countDown()
        }
    }

    /**
     * 枚举当前引擎可用的音色。
     *
     * ★ 各 ROM 完全不同（华为智慧语音、小米、Pixel 各有各的），
     *   所以只能运行时问引擎要，不能写死列表。
     */
    fun availableVoices(): List<SystemVoice> {
        // 引擎初始化是异步的，没就绪就一个音色都列不出来。
        // 最多等 3 秒。
        runCatching { initLatch.await(3, TimeUnit.SECONDS) }
        val t = tts ?: return emptyList()
        val out = mutableListOf<SystemVoice>()
        runCatching {
            t.voices?.forEach { v ->
                val name = v.name ?: return@forEach
                val loc = v.locale ?: return@forEach
                // ★ 列出**所有**中文相关音色：zh、zh-CN、zh-TW、zh-HK，
                //   还有 cmn（官话）等 language 标签。只按 startsWith("zh") 会漏掉一部分。
                val lang = loc.language.lowercase()
                if (lang.startsWith("zh") || lang == "cmn" || lang.startsWith("yue")) {
                    out += SystemVoice(
                        name = name,
                        needsNetwork = v.isNetworkConnectionRequired,
                        quality = v.quality,
                        locale = loc.toString(),
                        features = runCatching {
                            v.features?.toString()
                                ?.removePrefix("[")
                                ?.removeSuffix("]")
                                ?.split(",")
                                ?.map { it.trim().removePrefix("name=").trim('"') }
                                ?.filter { it.isNotEmpty() && it != "none" }
                                ?.joinToString(",")
                                ?.take(28)
                                .orEmpty()
                        }.getOrDefault(""),
                    )
                }
            }
        }
        return out.sortedBy { it.name }
    }

    /**
     * 用指定音色播一段样音（预听），播完切回原来的音色。
     *
     * 系统的 setVoice 是全局的，所以要先记住原音色。
     * [rate] 是设置页滑杆的**当前值**（可能还没保存），为空才回退到已保存值 ——
     * 否则拖完滑杆点试听，听到的还是旧速度。
     */
    fun preview(text: String, voiceName: String, rate: Float? = null) {
        val t = tts ?: return
        if (voiceName.isBlank()) return
        val original = runCatching { t.voice?.name }.getOrNull()
        runCatching {
            val v = t.voices?.firstOrNull { it.name == voiceName } ?: return
            if (t.setVoice(v) != TextToSpeech.SUCCESS) return
            t.setSpeechRate(rate ?: AgentSettings.ttsRate())
            t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "preview_${System.currentTimeMillis()}")
            // 播完切回
            main.postDelayed({
                runCatching {
                    original?.let { name ->
                        t.voices?.firstOrNull { it.name == name }?.let { t.setVoice(it) }
                    }
                }
            }, 3500)
            Dbg.i(TAG, "系统 TTS 预览音色：$voiceName")
        }
    }

    /** 切到指定音色（名字为空 = 用引擎默认）。 */
    fun applyVoice(voiceName: String) {
        val t = tts ?: return
        if (voiceName.isBlank()) return
        runCatching {
            val v = t.voices?.firstOrNull { it.name == voiceName } ?: return
            val r = t.setVoice(v)
            if (r == TextToSpeech.SUCCESS) {
                Dbg.i(TAG, "系统 TTS 切换音色：$voiceName")
            } else {
                Dbg.w(TAG, "切换音色失败 $voiceName code=$r")
            }
        }
    }

    /** 当前用的哪个系统引擎（华为智慧语音 = com.huawei.hiai）。 */
    private fun engineName(): String = runCatching {
        tts?.defaultEngine?.orEmpty() ?: "未知"
    }.getOrDefault("未知")

    override fun speak(text: String, onDone: () -> Unit, onError: (String) -> Unit) {
        val p = Pending(onDone, onError)
        if (text.isBlank()) {
            p.done()
            return
        }
        val gen = stopGen
        Thread {
            if (!initLatch.await(4, TimeUnit.SECONDS)) {
                Dbg.w(TAG, "系统 TTS 初始化超时")
            }
            if (!ready || !langOk) {
                // 只回调 onError，不再追加 onDone（V-04）
                main.post { p.error(lastError ?: "系统 TTS 不可用") }
                return@Thread
            }
            val id = "pilot_${seq.incrementAndGet()}"
            main.post {
                if (gen != stopGen) {
                    // 排队期间被 stop() 打断：不再出声
                    p.done()
                    return@post
                }
                // QUEUE_FLUSH 会冲掉之前的在途播报：先把它们按"播完"收尾，
                // 不留残留回调（引擎通常也会回 onStop，Pending 自带一次性保护）。
                drainPending()
                pending[id] = p
                // 设置页可能刚改过语速：每次播报前同步一次（只读一个 Float）。
                tts?.setSpeechRate(AgentSettings.ttsRate())
                val params = Bundle()
                val r = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, id)
                if (r != TextToSpeech.SUCCESS) {
                    Dbg.w(TAG, "系统 TTS speak 返回 $r")
                    pending.remove(id)?.error("系统 TTS 播报失败")
                }
            }
        }.apply { name = "sys-tts"; isDaemon = true }.start()
    }

    /** 把所有在途播报按"播完"收尾（被打断 / 被冲掉 / 释放时）。 */
    private fun drainPending() {
        if (pending.isEmpty()) return
        val ids = pending.keys.toList()
        ids.forEach { pending.remove(it)?.done() }
    }

    override fun stop() {
        stopGen++
        runCatching { tts?.stop() }
        drainPending()
    }

    override fun release() {
        stopGen++
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
        drainPending()
    }

    private companion object {
        const val TAG = "PilotTts"
    }
}
