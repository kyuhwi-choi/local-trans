package dev.localtrans.app.stt

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import dev.localtrans.app.audio.MicCapture
import dev.localtrans.app.config.SttConfig
import dev.localtrans.app.model.SttFiles

/**
 * 2단계 인식기 (sherpa-onnx 의 문장 단위 NeMo Parakeet 모델).
 * 스트리밍 인식이 끊은 구간의 음성을 통째로 다시 인식한다. 스트리밍보다 정확하지만 구간이 끝나야 쓸 수 있어서,
 * 화면의 실시간 자막은 스트리밍 결과로 띄우고 번역에는 이 결과를 넘긴다.
 */
class SecondPassStt(files: SttFiles, cfg: SttConfig) : AutoCloseable {

    private val recognizer = OfflineRecognizer(
        // assetManager 를 넘기지 않으면 파일 경로에서 모델을 읽는다.
        config = OfflineRecognizerConfig().apply {
            featConfig = FeatureConfig().apply {
                sampleRate = MicCapture.SAMPLE_RATE
                featureDim = 80
            }
            modelConfig = OfflineModelConfig().apply {
                transducer = OfflineTransducerModelConfig().apply {
                    encoder = files.encoder.absolutePath
                    decoder = files.decoder.absolutePath
                    joiner = files.joiner.absolutePath
                }
                tokens = files.tokens.absolutePath
                numThreads = cfg.secondPassThreads
                provider = "cpu"
                modelType = "nemo_transducer"
            }
            decodingMethod = "greedy_search"
        },
    )

    /** 한 번에 한 구간만 인식한다 (부르는 쪽에서 차례대로 부른다). */
    fun recognize(samples: FloatArray): String {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, MicCapture.SAMPLE_RATE)
            recognizer.decode(stream)
            return TextNormalizer.normalize(recognizer.getResult(stream).text)
        } finally {
            stream.release()
        }
    }

    override fun close() = recognizer.release()
}
