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

    private var seq = 0

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
                    tts?.setSpeechRate(1.0f)
                    // 应用用户选择的音色（可能为空 = 默认）
                    applyVoice(AgentSettings.load().systemTtsVoice)
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) = Unit
                        override fun onDone(utteranceId: String?) {
                            doneCallbacks.remove(utteranceId)?.invoke()
                        }

                        override fun onError(utteranceId: String?) {
                            doneCallbacks.remove(utteranceId)?.invoke()
                        }

                        override fun onError(utteranceId: String?, errorCode: Int) {
                            Dbg.w(TAG, "系统 TTS 播报出错 code=$errorCode")
                            doneCallbacks.remove(utteranceId)?.invoke()
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
                // 只列中文音色（和播报用途一致），但中文判断放宽到 zh
                if (loc.language.startsWith("zh")) {
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
        if (text.isBlank()) {
            onDone()
            return
        }
        Thread {
            if (!initLatch.await(4, TimeUnit.SECONDS)) {
                Dbg.w(TAG, "系统 TTS 初始化超时")
            }
            if (!ready || !langOk) {
                main.post {
                    onError(lastError ?: "系统 TTS 不可用")
                    onDone()
                }
                return@Thread
            }
            val id = "pilot_${++seq}"
            doneCallbacks[id] = onDone
            main.post {
                val params = Bundle()
                val r = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, id)
                if (r != TextToSpeech.SUCCESS) {
                    Dbg.w(TAG, "系统 TTS speak 返回 $r")
                    doneCallbacks.remove(id)?.invoke()
                    onError("播报失败")
                }
            }
        }.apply { name = "sys-tts"; isDaemon = true }.start()
    }

    override fun stop() {
        runCatching { tts?.stop() }
        doneCallbacks.values.forEach { runCatching { it() } }
        doneCallbacks.clear()
    }

    override fun release() {
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
    }

    private companion object {
        const val TAG = "PilotTts"
        val doneCallbacks = java.util.concurrent.ConcurrentHashMap<String, () -> Unit>()
    }
}
