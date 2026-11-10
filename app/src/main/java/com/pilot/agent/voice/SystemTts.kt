package com.pilot.agent.voice

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.pilot.agent.Dbg
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
                        lastError = "系统 TTS 没有中文语音包"
                        Dbg.w(TAG, lastError!!)
                    }
                    tts?.setSpeechRate(1.0f)
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
                    Dbg.i(TAG, "系统 TTS 就绪 lang=${if (langOk) "zh" else "缺中文包"}")
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
