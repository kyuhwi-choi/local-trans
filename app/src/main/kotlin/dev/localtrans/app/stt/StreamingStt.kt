package dev.localtrans.app.stt

import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import dev.localtrans.app.audio.MicCapture
import dev.localtrans.app.config.SttConfig
import dev.localtrans.app.model.SttFiles

/**
 * sherpa-onnx 스트리밍 인식기. 음성 조각을 넣으면 중간 결과와 확정 결과를 돌려준다.
 * [keepAudio] 이면 확정 결과에 그 구간의 음성을 함께 실어 2단계 인식에 쓸 수 있게 한다.
 */
class StreamingStt(files: SttFiles, cfg: SttConfig, keepAudio: Boolean = false) : AutoCloseable {

    sealed interface Event {
        /** 아직 말하는 중인 구간의 현재 인식 결과 */
        data class Partial(val text: String) : Event
        /** 침묵 또는 최대 길이로 끊긴 구간의 확정 결과. [audio] 는 그 구간의 음성(보관하지 않으면 null) */
        class Final(val text: String, val audio: FloatArray? = null) : Event
    }

    private val recognizer: OnlineRecognizer
    private val stream: OnlineStream
    private var lastPartial = ""

    // 구간은 최대 길이에서 강제로 끊기므로, 그보다 조금 넉넉하게만 잡아 둔다.
    private val segment = if (keepAudio) {
        SegmentAudio(((cfg.maxUtteranceSec + cfg.idleSilenceSec + 2f) * MicCapture.SAMPLE_RATE).toInt())
    } else {
        null
    }

    init {
        val config = OnlineRecognizerConfig().apply {
            featConfig = FeatureConfig().apply {
                sampleRate = MicCapture.SAMPLE_RATE
                featureDim = 80
            }
            modelConfig = OnlineModelConfig().apply {
                transducer = OnlineTransducerModelConfig().apply {
                    encoder = files.encoder.absolutePath
                    decoder = files.decoder.absolutePath
                    joiner = files.joiner.absolutePath
                }
                tokens = files.tokens.absolutePath
                numThreads = cfg.numThreads
                provider = "cpu"
            }
            endpointConfig = EndpointConfig().apply {
                rule1 = EndpointRule(false, cfg.idleSilenceSec, 0f)
                rule2 = EndpointRule(true, cfg.endpointSilenceSec, 0f)
                rule3 = EndpointRule(false, 0f, cfg.maxUtteranceSec)
            }
            enableEndpoint = true
            decodingMethod = "greedy_search"
        }
        // assetManager 를 넘기지 않으면 파일 경로에서 모델을 읽는다.
        recognizer = OnlineRecognizer(config = config)
        stream = recognizer.createStream()
    }

    /** 음성 조각을 넣고, 화면에 반영할 변화가 있으면 이벤트를 돌려준다. */
    fun accept(samples: FloatArray): Event? {
        segment?.append(samples)
        stream.acceptWaveform(samples, MicCapture.SAMPLE_RATE)
        while (recognizer.isReady(stream)) recognizer.decode(stream)
        val text = TextNormalizer.normalize(recognizer.getResult(stream).text)
        if (recognizer.isEndpoint(stream)) {
            recognizer.reset(stream)
            lastPartial = ""
            if (text.isEmpty()) {
                // 말이 막 시작될 때 글자 없이 끊기는 일이 있다. 모아 둔 음성을 다 버리면
                // 다음 구간을 다시 인식할 때 첫 단어를 잃으므로 끝부분은 남긴다.
                segment?.keepLast((KEEP_ON_EMPTY_SEC * MicCapture.SAMPLE_RATE).toInt())
                return null
            }
            return Event.Final(text, segment?.take())
        }
        if (text != lastPartial) {
            lastPartial = text
            return Event.Partial(text)
        }
        return null
    }

    fun reset() {
        recognizer.reset(stream)
        lastPartial = ""
        segment?.clear()
    }

    override fun close() {
        stream.release()
        recognizer.release()
    }

    private companion object {
        const val KEEP_ON_EMPTY_SEC = 1.5f
    }
}
