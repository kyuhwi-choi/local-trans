package dev.localtrans.app.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive

object MicCapture {
    const val SAMPLE_RATE = 16_000

    /**
     * 폰 내장 마이크에서 16kHz 모노 음성을 [chunkMs] 단위로 읽어 -1..1 범위 float 로 내보낸다.
     * RECORD_AUDIO 권한을 받은 뒤에 수집해야 한다.
     */
    @SuppressLint("MissingPermission")
    fun frames(chunkMs: Int = 100): Flow<FloatArray> = flow {
        val chunk = SAMPLE_RATE * chunkMs / 1000
        val minBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBytes, chunk * 2 * 4),
        )
        check(record.state == AudioRecord.STATE_INITIALIZED) { "마이크를 열 수 없습니다" }
        val buf = ShortArray(chunk)
        try {
            record.startRecording()
            while (coroutineContext.isActive) {
                val n = record.read(buf, 0, buf.size)
                check(n >= 0) { "마이크 읽기 오류 ($n)" }
                if (n > 0) emit(FloatArray(n) { buf[it] / 32768f })
            }
        } finally {
            runCatching { record.stop() }
            record.release()
        }
    }.flowOn(Dispatchers.IO)
}
