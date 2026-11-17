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
 * 파일이 끝나면 무음을 2초 더 내보낸 뒤 끝난다 (통역도 밀린 번역을 마치고 함께 끝난다).
 */
object WavSource {
    const val FILE_NAME = "debug-input.wav"
    private const val TAIL_SILENCE_MS = 2_000

    fun frames(file: File, chunkMs: Int = 100): Flow<FloatArray> = flow {
        val bytes = file.readBytes()
        val start = dataOffset(bytes)
        val chunk = MicCapture.SAMPLE_RATE * chunkMs / 1000
        var pos = start
        var next = SystemClock.elapsedRealtime()
        // 마지막 구간이 끊기고 번역될 수 있도록, 파일이 끝난 뒤에도 무음을 조금 더 흘려보낸 다음 끝낸다.
        var tail = TAIL_SILENCE_MS / chunkMs
        while (pos + 1 < bytes.size || tail-- > 0) {
            val out = FloatArray(chunk)
            var i = 0
            while (i < chunk && pos + 1 < bytes.size) {
                val sample = (bytes[pos].toInt() and 0xff) or (bytes[pos + 1].toInt() shl 8)
                out[i++] = sample / 32768f
                pos += 2
            }
            // 마이크처럼, 조각만큼의 시간이 흐른 뒤에 내보낸다.
            next += chunkMs
            delay((next - SystemClock.elapsedRealtime()).coerceAtLeast(0))
            emit(out)
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
