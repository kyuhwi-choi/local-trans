package dev.localtrans.app.audio

import java.io.File
import java.io.RandomAccessFile

/**
 * 16kHz 모노 16비트 WAV 로 음성을 이어 쓴다. 한 스레드에서만 쓴다.
 * 앱이 도중에 죽어도 그때까지의 음성을 읽을 수 있도록 머리말의 길이를 몇 초마다 고쳐 쓴다.
 */
internal class WavWriter(file: File) : AutoCloseable {
    private val out = RandomAccessFile(file, "rw").apply {
        setLength(0)
        write(header(0))
    }
    private var dataBytes = 0L
    private var sinceHeader = 0

    fun write(samples: FloatArray) {
        out.write(pcm16(samples))
        dataBytes += samples.size * 2
        sinceHeader += samples.size
        if (sinceHeader >= HEADER_EVERY_SAMPLES) updateHeader()
    }

    private fun updateHeader() {
        sinceHeader = 0
        out.seek(0)
        out.write(header(dataBytes))
        out.seek(HEADER_BYTES + dataBytes)
    }

    override fun close() {
        updateHeader()
        out.close()
    }

    companion object {
        const val HEADER_BYTES = 44L
        private const val HEADER_EVERY_SAMPLES = MicCapture.SAMPLE_RATE * 5

        /** -1..1 범위의 소리를 16비트 리틀 엔디언으로 바꾼다. 범위를 벗어난 값은 잘라 낸다. */
        fun pcm16(samples: FloatArray): ByteArray {
            val bytes = ByteArray(samples.size * 2)
            for (i in samples.indices) {
                val v = (samples[i] * 32768f).toInt().coerceIn(-32768, 32767)
                bytes[i * 2] = v.toByte()
                bytes[i * 2 + 1] = (v shr 8).toByte()
            }
            return bytes
        }

        fun header(dataBytes: Long): ByteArray {
            val rate = MicCapture.SAMPLE_RATE
            val h = ByteArray(HEADER_BYTES.toInt())
            fun text(at: Int, s: String) = s.forEachIndexed { i, c -> h[at + i] = c.code.toByte() }
            fun int(at: Int, v: Long, bytes: Int) = repeat(bytes) { h[at + it] = (v shr (8 * it)).toByte() }
            text(0, "RIFF"); int(4, 36 + dataBytes, 4); text(8, "WAVE")
            text(12, "fmt "); int(16, 16, 4); int(20, 1, 2); int(22, 1, 2)
            int(24, rate.toLong(), 4); int(28, rate * 2L, 4); int(32, 2, 2); int(34, 16, 2)
            text(36, "data"); int(40, dataBytes, 4)
            return h
        }
    }
}
