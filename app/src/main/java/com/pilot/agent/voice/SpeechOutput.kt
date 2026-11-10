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

    /** 合成并播放。onDone 在播完时回调，出错也要回调保证队列不卡住。 */
    fun speak(text: String, onDone: () -> Unit = {}, onError: (String) -> Unit = {})

    /** 打断当前播报 */
    fun stop()

    fun release()
}
