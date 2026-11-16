package dev.localtrans.app.audio

import android.os.SystemClock
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * 마이크 대신 WAV 파일(16kHz 모노 16비트 PCM)을 실제 말하는 속도로 흘려보낸다.
 * 방 안 소리에 좌우되지 않고 같은 입력으로 통역 흐름을 되풀이해 시험하기 위한 디버그용 입력이다.
 * 파일이 끝나면 무음을 계속 내보내 마지막 구간이 끊기도록 한다.
 */
object WavSource {
    const val FILE_NAME = "debug-input.wav"

    fun frames(file: File, chunkMs: Int = 100): Flow<FloatArray> = flow {
        val bytes = file.readBytes()
        val start = dataOffset(bytes)
        val chunk = MicCapture.SAMPLE_RATE * chunkMs / 1000
        var pos = start
        var next = SystemClock.elapsedRealtime()
        while (true) {
            val out = FloatArray(chunk)
            var i = 0
            while (i < chunk && pos + 1 < bytes.size) {
                val sample = (bytes[pos].toInt() and 0xff) or (bytes[pos + 1].toInt() shl 8)
                out[i++] = sample / 32768f
                pos += 2
            }
            emit(out)
            next += chunkMs
            delay((next - SystemClock.elapsedRealtime()).coerceAtLeast(0))
        }
    }.flowOn(Dispatchers.IO)

    /** RIFF 청크를 따라가 "data" 청크의 시작 위치를 찾는다. 못 찾으면 표준 헤더 길이(44)로 본다. */
    internal fun dataOffset(bytes: ByteArray): Int {
        var pos = 12
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = (bytes[pos + 4].toInt() and 0xff) or
                ((bytes[pos + 5].toInt() and 0xff) shl 8) or
                ((bytes[pos + 6].toInt() and 0xff) shl 16) or
                ((bytes[pos + 7].toInt() and 0xff) shl 24)
            if (id == "data") return pos + 8
            if (size < 0) break
            pos += 8 + size + (size and 1)
        }
        return 44
    }
}
