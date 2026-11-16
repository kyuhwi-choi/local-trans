package dev.localtrans.app.pipeline

import android.os.SystemClock
import android.util.Log
import dev.localtrans.app.BuildConfig
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * 디버그 빌드에서만 켜지는 진단 로그 (`adb logcat -s LocalTrans`).
 * 0.5초마다 마이크 음량과 인식에 걸린 시간을 찍어, 소리는 들어왔는데 인식이 비는 구간을 찾는 데 쓴다.
 */
internal object DebugTrace {
    private const val TAG = "LocalTrans"
    private const val WINDOW = 5 // 음성 조각 5개 = 0.5초

    val enabled = BuildConfig.DEBUG

    private var chunks = 0
    private var sumSquares = 0.0
    private var samples = 0
    private var decodeNanos = 0L
    private var windowStart = 0L

    fun log(message: String) {
        if (enabled) Log.d(TAG, message)
    }

    /** 음성 조각 하나를 인식기에 넣는 [block] 을 감싸서 음량과 소요 시간을 누적한다. */
    inline fun <T> chunk(audio: FloatArray, block: () -> T): T {
        if (!enabled) return block()
        val started = System.nanoTime()
        val result = block()
        record(audio, System.nanoTime() - started)
        return result
    }

    fun record(audio: FloatArray, nanos: Long) {
        if (chunks == 0) windowStart = SystemClock.elapsedRealtime()
        for (v in audio) sumSquares += v * v
        samples += audio.size
        decodeNanos += nanos
        if (++chunks < WINDOW) return
        val rms = sqrt(sumSquares / samples.coerceAtLeast(1))
        val db = if (rms > 0) 20 * log10(rms) else -120.0
        Log.d(
            TAG,
            "audio %.0f dB, decode %d ms, wall %d ms".format(
                db,
                decodeNanos / 1_000_000,
                SystemClock.elapsedRealtime() - windowStart,
            ),
        )
        chunks = 0
        sumSquares = 0.0
        samples = 0
        decodeNanos = 0
    }
}
