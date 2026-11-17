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
 * [silence] 를 주면 말이 잠깐 멈췄을 때 구간이 끝나기 전에 미리 알려, 번역을 앞당겨 시작할 수 있게 한다.
 * 구간을 끝내는 기준은 그대로 인식기의 규칙이다. [silence] 는 이 인식기가 맡아서 닫는다.
 */
class StreamingStt(
    files: SttFiles,
    private val cfg: SttConfig,
    keepAudio: Boolean = false,
    private val silence: SilenceDetector? = null,
) : AutoCloseable {

    sealed interface Event {
        /** 아직 말하는 중인 구간의 현재 인식 결과 */
        data class Partial(val text: String) : Event
        /**
         * 침묵 또는 최대 길이로 끊긴 구간의 확정 결과. [audio] 는 그 구간의 음성(보관하지 않으면 null).
         * [speculated] 이면 앞서 알린 [Tentative] 뒤로 말소리가 더 잡히지 않았다. 작은 말소리는 놓칠 수 있으므로,
         * 그때 시작한 번역은 이 구간 전체를 다시 인식한 문장과 같을 때만 쓴다.
         */
        class Final(val text: String, val audio: FloatArray? = null, val speculated: Boolean = false) : Event
        /** 말이 잠깐 멈췄다. 이대로 구간이 끝날 수 있으니 [audio] (지금까지의 음성)로 미리 번역을 시작해도 된다. */
        class Tentative(val audio: FloatArray) : Event
        /** [Tentative] 뒤에 말이 이어졌거나 아무 말도 아니었다. 미리 시작한 번역은 버린다. */
        data object Discard : Event
    }

    private val recognizer: OnlineRecognizer
    private val stream: OnlineStream
    private var lastPartial = ""
    /** [Event.Tentative] 를 알린 뒤 아직 말이 이어지지 않았는지 */
    private var tentative = false

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
        var resumed = false
        if (silence != null && silence.accept(samples) && tentative) {
            tentative = false
            resumed = true
        }
        if (recognizer.isEndpoint(stream)) {
            recognizer.reset(stream)
            lastPartial = ""
            val held = tentative
            tentative = false
            silence?.newSegment()
            if (text.isEmpty()) {
                // 말이 막 시작될 때 글자 없이 끊기는 일이 있다. 모아 둔 음성을 다 버리면
                // 다음 구간을 다시 인식할 때 첫 단어를 잃으므로 끝부분은 남긴다.
                segment?.keepLast((KEEP_ON_EMPTY_SEC * MicCapture.SAMPLE_RATE).toInt())
                return if (held || resumed) Event.Discard else null
            }
            return Event.Final(text, segment?.take(), speculated = held)
        }
        // 바뀐 글은 다음 조각에서 알린다 (lastPartial 을 그대로 둔다).
        if (resumed) return Event.Discard
        if (text != lastPartial) {
            lastPartial = text
            return Event.Partial(text)
        }
        if (silence != null && segment != null && !tentative && cfg.speculateSilenceSec > 0f &&
            silence.heard && silence.silentSec >= cfg.speculateSilenceSec
        ) {
            tentative = true
            return Event.Tentative(segment.peek())
        }
        return null
    }

    /**
     * 침묵을 기다리지 않고 지금 듣고 있던 구간을 바로 끝낸다. 인식된 말이 있으면 확정 결과로 돌려준다.
     */
    fun flush(): Event? {
        // 스트리밍 모델은 끝부분을 조금 늦게 내보내므로, 무음을 덧붙여 마지막 단어까지 받아 낸다.
        stream.acceptWaveform(FloatArray((FLUSH_PAD_SEC * MicCapture.SAMPLE_RATE).toInt()), MicCapture.SAMPLE_RATE)
        while (recognizer.isReady(stream)) recognizer.decode(stream)
        val text = TextNormalizer.normalize(recognizer.getResult(stream).text)
        recognizer.reset(stream)
        lastPartial = ""
        val held = tentative
        tentative = false
        silence?.newSegment()
        if (text.isEmpty()) {
            segment?.clear()
            return if (held) Event.Discard else null
        }
        return Event.Final(text, segment?.take(), speculated = held)
    }

    fun reset() {
        recognizer.reset(stream)
        lastPartial = ""
        tentative = false
        silence?.reset()
        segment?.clear()
    }

    override fun close() {
        stream.release()
        recognizer.release()
        silence?.close()
    }

    private companion object {
        const val KEEP_ON_EMPTY_SEC = 1.5f
        const val FLUSH_PAD_SEC = 0.6f
    }
}
