package com.pilot.agent.voice

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 极简 WAV 读取，只为了自检：拿一段现成音频喂给 ASR，验证模型和链路是否正常。
 * 不做重采样——上游给什么就要求什么（16k / 16bit / 单声道）。
 */
object WavReader {

    class Pcm(val samples: ShortArray, val sampleRate: Int, val channels: Int)

    fun read(file: File): Pcm? {
        if (!file.exists()) return null
        val bytes = file.readBytes()
        if (bytes.size < 44) return null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (buf.int != 0x46464952) return null // "RIFF"

        var sampleRate = 0
        var channels = 0
        var bitsPerSample = 0
        var dataOffset = -1
        var dataLen = 0

        var pos = 12
        while (pos + 8 <= bytes.size) {
            buf.position(pos)
            val id = buf.int
            val size = buf.int
            val body = pos + 8
            when (id) {
                0x20746d66 -> { // "fmt "
                    buf.position(body)
                    buf.short // audioFormat
                    channels = buf.short.toInt()
                    sampleRate = buf.int
                    buf.int // byteRate
                    buf.short // blockAlign
                    bitsPerSample = buf.short.toInt()
                }

                0x61746164 -> { // "data"
                    dataOffset = body
                    dataLen = size.coerceAtMost(bytes.size - body)
                }
            }
            if (id == 0x61746164) break
            pos = body + size + (size and 1)
        }

        if (dataOffset < 0 || sampleRate <= 0 || bitsPerSample != 16) return null
        val count = dataLen / 2
        val samples = ShortArray(count)
        buf.position(dataOffset)
        for (i in 0 until count) samples[i] = buf.short
        return Pcm(samples, sampleRate, channels)
    }
}
