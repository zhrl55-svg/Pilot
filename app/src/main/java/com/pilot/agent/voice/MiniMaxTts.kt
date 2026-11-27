package com.pilot.agent.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import com.pilot.agent.Dbg
import com.pilot.agent.agent.AgentSettings
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/**
 * MiniMax 云端语音合成。
 *
 * 音色比系统 TTS 自然得多，代价是每句多 0.5~1.5 秒网络往返，且没网就哑了。
 * 所以做成可选项，默认还是系统 TTS。
 *
 * ## ★ 接口细节（实测，别凭印象改）
 * ```
 * POST {base}/t2a_v2
 * → data.audio 是 **hex 字符串**，不是 base64
 *   494433 解码就是 "ID3"，即 MP3 文件头
 * ```
 * 解码后写临时文件，用 MediaPlayer 播。
 */
/** MiniMax 音色选项（voice_id + 中文名）。 */
data class MiniMaxVoice(val id: String, val label: String)

class MiniMaxTts(private val context: Context) : SpeechOutput {

    companion object {
        private const val TAG = "PilotTts"
        private const val MAX_BODY_CHARS = 4 * 1024 * 1024

        /**
         * 可选音色。
         *
         * ★ 用的是 MiniMax 官方系统音色 ID（male-qn-qingse / female-shaonv 等），
         *   这些是**免费/通用**音色，填错会返回业务错误 1013。
         */
        val VOICES: List<MiniMaxVoice> = listOf(
            MiniMaxVoice("male-qn-qingse", "青涩青年男"),
            MiniMaxVoice("male-qn-jingying", "精英青年男"),
            MiniMaxVoice("male-qn-badao", "霸道青年男"),
            MiniMaxVoice("male-qn-daxuesheng", "青年大学生男"),
            MiniMaxVoice("female-shaonv", "少女女声"),
            MiniMaxVoice("female-yujie", "御姐女声"),
            MiniMaxVoice("female-chengshu", "成熟女声"),
            MiniMaxVoice("female-tianmei", "甜美女声"),
            MiniMaxVoice("presenter_male", "男性主持人"),
            MiniMaxVoice("presenter_female", "女性主持人"),
            MiniMaxVoice("audiobook_male_1", "有声书男声"),
            MiniMaxVoice("audiobook_female_1", "有声书女声"),
        )
    }

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var player: MediaPlayer? = null

    @Volatile
    private var currentFile: File? = null

    /**
     * 播放代数。stop() 一递增，所有在途的合成结果立即作废。
     *
     * 不加这个会出现「打断了它还接着播」：合成跑在后台线程，stop 时它还在路上，
     * 几百毫秒后照样 play()。
     */
    @Volatile
    private var playGen = 0

    @Volatile
    var lastError: String? = null
        private set

    /** 一条播报的终止回调：onDone / onError 恰好其一、只触发一次（V-04）。 */
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
     * 当前在途（合成中或播放中）的播报。
     *
     * ★ stop() 时必须给它收尾：以前合成被作废、或 MediaPlayer 被 stop 释放后，
     *   onCompletion 永远不来，Speaker 持有的 AudioGate 令牌就泄漏了——在真正的
     *   引用计数下这会让麦克风永久处于"放音中"。
     */
    @Volatile
    private var current: Pending? = null

    /** 收掉当前在途播报（按"播完/被打断"处理）。 */
    private fun finishCurrentAsDone() {
        val p = current
        current = null
        p?.done()
    }

    override fun speak(text: String, onDone: () -> Unit, onError: (String) -> Unit) {
        val p = Pending(onDone, onError)
        if (text.isBlank()) {
            p.done()
            return
        }
        val settings = AgentSettings.load()
        if (settings.apiKey.isBlank()) {
            lastError = "没有 API Key"
            p.error(lastError ?: "没有 API Key")
            return
        }
        // 新播报顶掉旧播报：旧的按"被打断"收尾
        finishCurrentAsDone()
        current = p
        val gen = ++playGen
        Thread {
            val t0 = System.currentTimeMillis()
            val bytes = synth(text, settings)
            if (gen != playGen) {
                // 已被 stop() 或下一条播报作废；收尾由 stop()/下一条 speak 负责，这里兜底一次
                Dbg.i(TAG, "MiniMax TTS 结果作废 gen=$gen")
                main.post { p.done() }
                return@Thread
            }
            if (bytes == null || bytes.isEmpty()) {
                Dbg.w(TAG, "MiniMax TTS 合成失败：$lastError")
                main.post {
                    if (current === p) current = null
                    // 只回调 onError（V-04），由 Speaker 决定是否降级系统 TTS
                    p.error(lastError ?: "合成失败")
                }
                return@Thread
            }
            Dbg.i(TAG, "MiniMax TTS ${bytes.size}B ${System.currentTimeMillis() - t0}ms")
            main.post {
                if (gen == playGen) play(bytes, p) else p.done()
            }
        }.apply { name = "mm-tts"; isDaemon = true }.start()
    }

    override fun stop() {
        playGen++
        stopInternal()
        finishCurrentAsDone()
    }

    override fun release() {
        playGen++
        stopInternal()
        finishCurrentAsDone()
    }

    private fun play(bytes: ByteArray, p: Pending) {
        // 试听等非 speak 路径会顶掉正在播的那条：先给它收尾，避免闸门令牌泄漏
        if (current !== p) finishCurrentAsDone()
        stopInternal()
        runCatching {
            val f = File(context.cacheDir, "tts_${System.currentTimeMillis()}.mp3")
            f.writeBytes(bytes)
            currentFile = f

            val mp = MediaPlayer()
            player = mp
            runCatching {
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
            }
            mp.setDataSource(f.absolutePath)
            mp.setOnCompletionListener {
                stopInternal()
                if (current === p) current = null
                p.done()
            }
            mp.setOnErrorListener { _, what, extra ->
                Dbg.w(TAG, "MediaPlayer 错误 what=$what extra=$extra")
                stopInternal()
                if (current === p) current = null
                p.error("播放失败")
                true
            }
            mp.prepare()
            mp.start()
        }.onFailure {
            lastError = "${it.javaClass.simpleName}: ${it.message}"
            Dbg.e(TAG, "播放异常 $lastError")
            stopInternal()
            if (current === p) current = null
            // 只回调 onError（V-04）
            p.error("播放异常")
        }
    }

    /** 必须在主线程：MediaPlayer.release 在别的线程容易崩 */
    private fun stopInternal() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        currentFile?.let { runCatching { it.delete() } }
        currentFile = null
    }

    /**
     * 用**指定音色**合成一段话（预听用），不改变当前设置。
     *
     * [rate] 是设置页滑杆的当前值（可能还没保存），为空才用已保存的语速。
     */
    fun preview(text: String, voiceId: String, rate: Float? = null, onDone: () -> Unit) {
        val stored = AgentSettings.load()
        val s = if (rate != null) stored.copy(ttsRate = rate) else stored
        if (s.apiKey.isBlank()) {
            Dbg.w(TAG, "预览失败：没有 API Key")
            onDone()
            return
        }
        val gen = ++playGen
        Thread {
            val bytes = synth(text, s, voiceId)
            if (bytes == null || bytes.isEmpty()) {
                Dbg.w(TAG, "预览合成失败：$lastError")
                main.post { onDone() }
                return@Thread
            }
            main.post {
                if (gen == playGen) play(bytes, Pending(onDone, { onDone() })) else onDone()
            }
        }.apply { name = "mm-tts-preview"; isDaemon = true }.start()
    }

    private fun synth(text: String, s: AgentSettings, voiceOverride: String? = null): ByteArray? {
        val payload = JSONObject()
            .put("model", s.ttsModel)
            .put("text", text)
            .put(
                "voice_setting",
                JSONObject()
                    .put("voice_id", voiceOverride ?: s.ttsVoiceId)
                    .put("speed", s.ttsRate.toDouble().coerceIn(0.5, 2.0))
                    .put("vol", 1.0)
                    .put("pitch", 0)
            )
            .put(
                "audio_setting",
                JSONObject()
                    .put("sample_rate", 32000)
                    .put("bitrate", 128000)
                    .put("format", "mp3")
                    .put("channel", 1)
            )
            .toString()

        var conn: HttpURLConnection? = null
        return try {
            val base = s.baseUrl.trimEnd('/')
            conn = (URL("$base/t2a_v2").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8_000
                readTimeout = 20_000
                doOutput = true
                setRequestProperty("Authorization", "Bearer ${s.apiKey}")
                setRequestProperty("Content-Type", "application/json")
            }
            conn.outputStream.use { it.write(payload.toByteArray()) }

            val code = conn.responseCode
            // P2：限制响应体大小，避免异常大响应 string→byte[] 双份占内存。
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { reader ->
                    val buf = CharArray(8192)
                    val sb = StringBuilder()
                    var total = 0
                    while (total < MAX_BODY_CHARS) {
                        val n = reader.read(buf, 0, minOf(buf.size, MAX_BODY_CHARS - total))
                        if (n < 0) break
                        sb.append(buf, 0, n)
                        total += n
                    }
                    sb.toString()
                }.orEmpty()

            if (code !in 200..299) {
                lastError = "HTTP $code ${body.take(160)}"
                return null
            }
            val json = JSONObject(body)
            val bizCode = json.optJSONObject("base_resp")?.optInt("status_code", 0) ?: 0
            if (bizCode != 0) {
                lastError = "业务错误 $bizCode: ${json.optJSONObject("base_resp")?.optString("status_msg")}"
                return null
            }
            val hex = json.optJSONObject("data")?.optString("audio").orEmpty()
            if (hex.isBlank()) {
                lastError = "返回没有 audio 字段"
                return null
            }
            hexToBytes(hex)
        } catch (t: Throwable) {
            lastError = "${t.javaClass.simpleName}: ${t.message}"
            null
        } finally {
            conn?.disconnect()
        }
    }

    /** 返回的是十六进制串，不是 base64 */
    private fun hexToBytes(s: String): ByteArray? {
        val len = s.length / 2
        if (len == 0) return null
        val out = ByteArray(len)
        for (i in 0 until len) {
            val hi = Character.digit(s[i * 2], 16)
            val lo = Character.digit(s[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}
