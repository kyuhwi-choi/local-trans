package dev.localtrans.app.stt

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import dev.localtrans.app.audio.MicCapture

/**
 * 지금 듣고 있는 구간에서 말소리가 있었는지, 그 뒤로 얼마나 조용했는지를 센다.
 * 말소리 판정(VAD)과 떼어 놓아 테스트할 수 있게 했다.
 */
internal class SilenceTracker(private val minSilenceSec: Float) {
    /** 이 구간에서 말소리가 한 번이라도 있었는지 */
    var heard = false
        private set

    /** 마지막 말소리 뒤로 이어진 침묵(초). 말소리가 아직 없었으면 0 */
    var silentSec = 0f
        private set

    /** 음성 조각 하나를 반영한다. [speaking] 은 그 조각이 끝난 시점에 말하는 중인지. */
    fun update(speaking: Boolean, chunkSec: Float) {
        if (speaking) {
            heard = true
            silentSec = 0f
        } else if (heard) {
            // VAD 는 침묵이 minSilenceSec 만큼 이어진 뒤에야 말이 끝났다고 알린다.
            silentSec = if (silentSec == 0f) minSilenceSec else silentSec + chunkSec
        }
    }

    /** 구간이 끊겼을 때 부른다. */
    fun newSegment() {
        heard = false
        silentSec = 0f
    }
}

/**
 * 말이 멈춘 것을 인식기보다 빨리 알아챈다 (sherpa-onnx 의 Silero VAD, APK 에 든 약 0.6MB 모델).
 * 스트리밍 인식기는 0.5초쯤마다 한 번씩만 결과를 내서 침묵을 늦게 알아채므로, 미리 번역을 시작할 때를 잡는 데 쓴다.
 */
class SilenceDetector(assets: AssetManager) : AutoCloseable {

    private val vad = Vad(
        assetManager = assets,
        config = VadModelConfig().apply {
            sileroVadModelConfig = SileroVadModelConfig().apply {
                model = MODEL_ASSET
                threshold = 0.5f
                minSilenceDuration = MIN_SILENCE_SEC
                minSpeechDuration = 0.1f
                windowSize = 512
            }
            sampleRate = MicCapture.SAMPLE_RATE
            numThreads = 1
            provider = "cpu"
        },
    )
    private val tracker = SilenceTracker(MIN_SILENCE_SEC)

    val heard get() = tracker.heard
    val silentSec get() = tracker.silentSec

    /** 음성 조각을 넣는다. 그 조각이 끝난 시점에 말하는 중이면 true */
    fun accept(samples: FloatArray): Boolean {
        vad.acceptWaveform(samples)
        // 말소리 구간의 음성은 쓰지 않으므로 쌓이지 않게 비운다.
        while (!vad.empty()) vad.pop()
        val speaking = vad.isSpeechDetected()
        tracker.update(speaking, samples.size.toFloat() / MicCapture.SAMPLE_RATE)
        return speaking
    }

    fun newSegment() = tracker.newSegment()

    fun reset() {
        vad.reset()
        tracker.newSegment()
    }

    override fun close() = vad.release()

    companion object {
        const val MODEL_ASSET = "silero_vad.onnx"
        private const val MIN_SILENCE_SEC = 0.1f
    }
}
