package com.pilot.navigation

import android.content.Context
import java.io.File

/**
 * 语音状态（跨进程共享）。
 *
 * ## 为什么要这个东西
 * 导航界面的语音按钮要显示三种状态：
 * - **播报中** 🔈（动态）—— 提示「正在出声，别说话」
 * - **录音中** 🎤（动态）—— 提示「正在听你说」
 * - **空闲** 🎤（静态）
 *
 * 但状态的产生方是**主进程**（TTS / ASR 都在主进程），
 * 消费方是**导航界面**（`:navigation` 进程）。跨进程传状态有两条路：
 * 1. AIDL 回调 —— 要额外定义接口 + 管理注册/反注册生命周期，为三个状态值太重
 * 2. **写文件 + 轮询** —— 一个几百字节的 JSON，readText 不到 1ms
 *
 * 选 2。轮询间隔 1 秒，只有状态变化时才动 UI。
 *
 * ## 顺带承载 ASR 结果
 * 导航界面要「语音识别到地址后自动填入搜索框」，
 * 所以这里也带一个 `lastAsrText` 字段——
 * 主进程识别完成后写进来，导航界面读到新内容就填进输入框。
 */
object VoiceState {

    private const val TAG = "PilotNavVoiceState"
    private const val FILE = "voice_state.json"

    /** 空闲（没播报也没录音）。 */
    const val IDLE = "idle"

    /** 正在播报（TTS 出声）。 */
    const val SPEAKING = "speaking"

    /** 正在录音（ASR 听）。 */
    const val RECORDING = "recording"

    /**
     * 读当前状态。
     * 读不到/格式坏都返回 [IDLE]——UI 那边按空闲处理最安全。
     */
    fun read(context: Context): String = runCatching {
        val f = File(context.filesDir, FILE)
        if (!f.isFile) return IDLE
        // 格式极简，不用 JSON 解析器：一行 "state|asrText|timestamp"
        val line = f.readText().trim()
        if (line.isEmpty()) return IDLE
        val parts = line.split('|')
        parts.firstOrNull()?.takeIf { it.isNotBlank() } ?: IDLE
    }.getOrDefault(IDLE)

    /**
     * 读最近一次 ASR 识别文本（含时间戳，调用方自己判断是不是新的）。
     * @return (文本, 时间戳) ；没有则 null
     */
    fun readAsr(context: Context): Pair<String, Long>? = runCatching {
        val f = File(context.filesDir, FILE)
        if (!f.isFile) return null
        val parts = f.readText().trim().split('|')
        if (parts.size < 3) return null
        val text = parts[1]
        val ts = parts[2].toLongOrNull() ?: 0L
        if (text.isBlank()) null else (text to ts)
    }.getOrNull()

    /**
     * 写状态（主进程调用）。
     *
     * 保留 asrText 不清空——写状态的是 TTS，写文本的是 ASR，
     * 两者互不覆盖对方的字段很关键（否则播报一开始就把识别结果抹了）。
     */
    fun write(context: Context, state: String, asrText: String? = null) {
        runCatching {
            val f = File(context.filesDir, FILE)
            val old = if (f.isFile) f.readText().trim().split('|') else emptyList()
            val keepText = asrText ?: old.getOrNull(1).orEmpty()
            val keepTs = if (asrText != null) System.currentTimeMillis()
            else old.getOrNull(2)?.toLongOrNull() ?: 0L
            val tmp = File(f.parentFile, "$FILE.tmp")
            tmp.writeText("$state|$keepText|$keepTs")
            if (!tmp.renameTo(f)) {
                f.writeText("$state|$keepText|$keepTs")
                tmp.delete()
            }
        }.onFailure { NLog.w(TAG, "写语音状态失败 ${it.message}") }
    }
}
