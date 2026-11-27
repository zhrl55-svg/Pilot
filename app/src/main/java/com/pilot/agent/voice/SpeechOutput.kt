package com.pilot.agent.voice

/**
 * 语音播报接口。
 *
 * 两套实现：
 * - [SystemTts]：Android 系统 TTS，零延迟、离线可用，音色一般
 * - [MiniMaxTts]：云端合成，音色自然，但要网络往返（约 0.5~1.5 秒）
 *
 * 默认用系统 TTS——agent 的播报都是短句（"已打开设置"），系统引擎够用且不花钱。
 */
interface SpeechOutput {

    /**
     * 合成并播放。
     *
     * ★ 回调契约（审核 V-04）：每次 speak **恰好**触发一次终止回调——
     *   - 正常播完 / 被 [stop] 打断 / 被后一条播报冲掉 → [onDone]
     *   - 失败（引擎不可用、合成失败、播放出错）→ [onError]，**之后不再调 onDone**
     *
     * 以前实现会 onError 之后再 onDone，[Speaker] 降级到系统 TTS 时被 onDone 提前关闸、
     * 触发"播报结束→自动聆听"，兜底播报还没出声麦克风就开了。
     * 调用方仍应自行做一次性保护（[Speaker] 已做）。
     */
    fun speak(text: String, onDone: () -> Unit = {}, onError: (String) -> Unit = {})

    /** 打断当前播报（被打断的那条按 onDone 收尾） */
    fun stop()

    fun release()
}
