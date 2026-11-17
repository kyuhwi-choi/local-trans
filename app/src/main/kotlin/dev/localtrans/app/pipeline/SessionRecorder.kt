package dev.localtrans.app.pipeline

import android.os.SystemClock
import dev.localtrans.app.audio.MicCapture
import dev.localtrans.app.audio.WavWriter
import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 통역 한 번을 파일로 남긴다 (디버그 빌드 전용): 들은 음성(`이름.wav`)과, 구간마다 인식·번역한 결과와 시각(`이름.jsonl`).
 * 남긴 음성은 debug-input.wav 로 다시 넣어 같은 입력으로 설정을 비교하는 데 쓰고(bench/e2e),
 * 기록의 시각은 모두 음성이 시작된 뒤로 흐른 시간(ms)이라 음성 속 위치와 바로 견줄 수 있다.
 */
internal class SessionRecorder(dir: File, name: String, recordAudio: Boolean, header: JsonObject) : AutoCloseable {

    private val wav = if (recordAudio) WavWriter(File(dir, "$name.wav")) else null
    private val log = File(dir, "$name.jsonl").bufferedWriter()
    @Volatile private var startedAt = 0L

    init {
        write(header)
    }

    /** 음성이 시작된 뒤로 흐른 시간(ms). 첫 음성이 들어오기 전에는 0 */
    fun now(): Long = if (startedAt == 0L) 0 else SystemClock.elapsedRealtime() - startedAt

    /** 들어온 음성 조각을 그대로 남긴다. 인식하는 쪽 스레드에서만 부른다. */
    fun audio(samples: FloatArray) {
        if (startedAt == 0L) {
            // 조각은 다 녹음된 뒤에 들어오므로, 음성의 시작은 조각 길이만큼 앞이다.
            startedAt = SystemClock.elapsedRealtime() - samples.size * 1000L / MicCapture.SAMPLE_RATE
        }
        wav?.write(samples)
    }

    fun event(type: String, fields: JsonObjectBuilder.() -> Unit = {}) {
        write(buildJsonObject {
            put("type", type)
            put("ms", now())
            fields()
        })
    }

    @Synchronized
    private fun write(line: JsonObject) {
        log.write(line.toString())
        log.newLine()
        log.flush()
    }

    override fun close() {
        runCatching { event("end") }
        runCatching { log.close() }
        runCatching { wav?.close() }
    }
}
