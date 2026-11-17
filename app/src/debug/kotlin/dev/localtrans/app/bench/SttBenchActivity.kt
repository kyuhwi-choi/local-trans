package dev.localtrans.app.bench

import android.os.Bundle
import android.os.Debug
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import dev.localtrans.app.LocalTransApp
import dev.localtrans.app.audio.MicCapture
import dev.localtrans.app.audio.WavSource
import dev.localtrans.app.config.SttConfig
import dev.localtrans.app.stt.TextNormalizer
import dev.localtrans.app.ui.LocalTransTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 음성 인식 벤치마크 실행기 (디버그 빌드 전용, bench/stt/run.sh 가 띄운다).
 * files/bench-stt/engines.json 에 적힌 모델을 차례로 올려 files/bench-stt/audio 의 음성을 인식하고,
 * 모델마다 output-<id>.jsonl 에 인식 결과와 처리 시간을 적는다. 다 끝나면 done-stt.json 을 남긴다.
 * 메모리 사용량이 섞이지 않도록 run.sh 는 모델 하나마다 앱을 새로 띄운다.
 *
 * 스트리밍 모델은 앱과 같은 방식(0.1초 조각, 같은 구간 끊기 규칙)으로 넣되 기다리지 않고 연달아 넣는다.
 * 문장 단위 모델은 발화 하나를 통째로 넣는다.
 */
class SttBenchActivity : ComponentActivity() {

    private val status = mutableStateOf("준비 중")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            LocalTransTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Text(status.value, modifier = Modifier.systemBarsPadding().padding(24.dp))
                }
            }
        }
        lifecycleScope.launch { status.value = withContext(Dispatchers.Default) { run() } }
    }

    private class Item(val id: String, val file: File)

    /** 발화 하나의 인식 결과. stepMaxMs 는 0.1초 조각 하나를 처리하는 데 걸린 가장 긴 시간(스트리밍만). */
    private class Outcome(val text: String, val ms: Long, val stepMaxMs: Long)

    private fun interface Engine : AutoCloseable {
        fun recognize(samples: FloatArray): Outcome
        override fun close() {}
    }

    private fun run(): String {
        val dir = File(getExternalFilesDir(null), "bench-stt")
        // 앱이 먼저 폴더를 만들어 둬야 adb push 로 넣은 파일을 읽을 수 있다.
        File(dir, "audio").mkdirs()
        File(dir, "models").mkdirs()
        val done = File(dir, "done-stt.json")
        done.delete()
        val enginesFile = File(dir, "engines.json")
        val inputFile = File(dir, "input.jsonl")
        if (!enginesFile.isFile || !inputFile.isFile) return "입력 파일이 없습니다: $dir"

        return try {
            val stt = (application as LocalTransApp).config.config.value.stt
            val bySet = inputFile.readLines().filter { it.isNotBlank() }.map {
                val row = Json.parseToJsonElement(it).jsonObject
                val id = row.getValue("id").jsonPrimitive.content
                row.getValue("set").jsonPrimitive.content to Item(id, File(dir, "audio/$id.wav"))
            }.groupBy({ it.first }, { it.second }).values
            // 세트를 번갈아 가며 잰다. 너무 느려 중간에 그만두는 모델도 모든 세트를 고르게 재게 된다.
            val items = (0 until bySet.maxOf { it.size }).flatMap { i -> bySet.mapNotNull { it.getOrNull(i) } }
            val engines = Json.parseToJsonElement(enginesFile.readText()).jsonArray.map { it.jsonObject }
            val summaries = engines.map { spec -> runEngine(dir, spec, items, stt) }
            done.writeText(buildJsonObject {
                put("count", items.size)
                put("engines", buildJsonArray { summaries.forEach { add(it) } })
            }.toString())
            report("끝 (모델 ${engines.size}개, 발화 ${items.size}개)")
        } catch (t: Throwable) {
            Log.e(TAG, "bench failed", t)
            done.writeText(buildJsonObject { put("error", t.toString()) }.toString())
            report("실패: $t")
        }
    }

    private fun runEngine(dir: File, spec: JsonObject, items: List<Item>, stt: SttConfig): JsonObject {
        val id = spec.getValue("id").jsonPrimitive.content
        val threads = spec["threads"]?.jsonPrimitive?.int ?: stt.numThreads
        val output = File(dir, "output-$id.jsonl").apply { delete() }
        return try {
            report("$id: 모델 올리는 중")
            Runtime.getRuntime().gc()
            val baseKb = Debug.getPss()
            val started = System.nanoTime()
            val engine = create(File(dir, "models"), id, spec, threads, stt)
            val loadMs = (System.nanoTime() - started) / 1_000_000
            var peakKb = Debug.getPss()
            var count = 0
            // engines.json 의 limit: 빨리 훑어볼 때 발화 수를 줄인다.
            var limit = minOf(items.size, spec["limit"]?.jsonPrimitive?.int ?: items.size)
            var slow = false
            var audioMs = 0L
            var spentMs = 0L

            engine.use {
                // 첫 실행은 준비 작업 때문에 느리므로 재지 않는다.
                it.recognize(read(items.first().file))
                output.bufferedWriter().use { writer ->
                    for ((index, item) in items.withIndex()) {
                        if (index >= limit) break
                        val samples = read(item.file)
                        val outcome = it.recognize(samples)
                        count++
                        audioMs += samples.size * 1000L / MicCapture.SAMPLE_RATE
                        spentMs += outcome.ms
                        // 음성 길이보다 처리 시간이 길면 실제로 쓸 수 없는 모델이다. 일부만 재고 넘어간다.
                        if (count == SLOW_CHECK && spentMs > audioMs && limit > SLOW_LIMIT) {
                            limit = SLOW_LIMIT
                            slow = true
                        }
                        writer.write(buildJsonObject {
                            put("id", item.id)
                            put("out", outcome.text)
                            put("ms", outcome.ms)
                            put("step_max_ms", outcome.stepMaxMs)
                            put("audio_ms", samples.size * 1000L / MicCapture.SAMPLE_RATE)
                        }.toString())
                        writer.newLine()
                        writer.flush()
                        if (index % 10 == 0) {
                            peakKb = maxOf(peakKb, Debug.getPss())
                            report("$id ${index + 1}/$limit")
                        }
                    }
                }
            }
            buildJsonObject {
                put("id", id)
                put("count", count)
                put("stopped_slow", slow)
                put("threads", threads)
                put("load_ms", loadMs)
                put("base_pss_kb", baseKb)
                put("peak_pss_kb", peakKb)
            }
        } catch (t: Throwable) {
            // 한 모델이 실패해도 나머지는 계속 잰다.
            Log.e(TAG, "$id failed", t)
            buildJsonObject { put("id", id); put("error", t.toString()) }
        }
    }

    /** 모델 파일은 models 폴더에 "<모델 id>--<파일 이름>" 으로 들어 있다. */
    private fun create(modelDir: File, id: String, spec: JsonObject, threads: Int, stt: SttConfig): Engine {
        val files = spec.getValue("files").jsonObject
        fun path(role: String) = File(modelDir, "$id--${files.getValue(role).jsonPrimitive.content}").also {
            require(it.isFile) { "모델 파일이 없습니다: $it" }
        }.absolutePath
        val type = spec["modelType"]?.jsonPrimitive?.content.orEmpty()
        val features = FeatureConfig().apply {
            sampleRate = MicCapture.SAMPLE_RATE
            featureDim = 80
        }

        return when (val kind = spec.getValue("kind").jsonPrimitive.content) {
            "online-transducer" -> streaming(OnlineRecognizerConfig().apply {
                featConfig = features
                modelConfig = OnlineModelConfig().apply {
                    transducer = OnlineTransducerModelConfig().apply {
                        encoder = path("encoder")
                        decoder = path("decoder")
                        joiner = path("joiner")
                    }
                    tokens = path("tokens")
                    numThreads = threads
                    provider = "cpu"
                    modelType = type
                }
                endpointConfig = EndpointConfig().apply {
                    rule1 = EndpointRule(false, stt.idleSilenceSec, 0f)
                    rule2 = EndpointRule(true, stt.endpointSilenceSec, 0f)
                    rule3 = EndpointRule(false, 0f, stt.maxUtteranceSec)
                }
                enableEndpoint = true
                decodingMethod = "greedy_search"
            })

            "offline-transducer", "offline-moonshine", "offline-whisper" -> offline(OfflineRecognizerConfig().apply {
                featConfig = features
                modelConfig = OfflineModelConfig().apply {
                    when (kind) {
                        "offline-transducer" -> transducer = OfflineTransducerModelConfig().apply {
                            encoder = path("encoder")
                            decoder = path("decoder")
                            joiner = path("joiner")
                        }
                        "offline-moonshine" -> moonshine = OfflineMoonshineModelConfig().apply {
                            encoder = path("encoder")
                            mergedDecoder = path("mergedDecoder")
                        }
                        else -> whisper = OfflineWhisperModelConfig().apply {
                            encoder = path("encoder")
                            decoder = path("decoder")
                            language = "en"
                        }
                    }
                    tokens = path("tokens")
                    numThreads = threads
                    provider = "cpu"
                    modelType = type
                }
                decodingMethod = "greedy_search"
            })

            else -> error("모르는 모델 종류: $kind")
        }
    }

    private fun streaming(config: OnlineRecognizerConfig): Engine {
        val recognizer = OnlineRecognizer(config = config)
        val chunk = MicCapture.SAMPLE_RATE / 10
        // 실제 사용에서는 말 앞뒤로 조용한 구간이 있다. 시험 음성은 말이 곧바로 시작해 첫 단어를 놓치기 쉬우므로
        // 앞에 0.5초, 뒤에는 마지막 구간이 끊길 만큼(1초)의 무음을 붙인다.
        val lead = FloatArray(MicCapture.SAMPLE_RATE / 2)
        val tail = FloatArray(MicCapture.SAMPLE_RATE)
        return object : Engine {
            override fun recognize(samples: FloatArray): Outcome {
                val stream = recognizer.createStream()
                val finals = StringBuilder()
                var total = 0L
                var stepMax = 0L
                fun feed(part: FloatArray) {
                    val started = System.nanoTime()
                    stream.acceptWaveform(part, MicCapture.SAMPLE_RATE)
                    while (recognizer.isReady(stream)) recognizer.decode(stream)
                    if (recognizer.isEndpoint(stream)) {
                        finals.append(recognizer.getResult(stream).text).append(' ')
                        recognizer.reset(stream)
                    }
                    val took = System.nanoTime() - started
                    total += took
                    stepMax = maxOf(stepMax, took)
                }
                try {
                    feed(lead)
                    var pos = 0
                    while (pos < samples.size) {
                        val end = minOf(pos + chunk, samples.size)
                        feed(samples.copyOfRange(pos, end))
                        pos = end
                    }
                    feed(tail)
                    val started = System.nanoTime()
                    stream.inputFinished()
                    while (recognizer.isReady(stream)) recognizer.decode(stream)
                    finals.append(recognizer.getResult(stream).text)
                    total += System.nanoTime() - started
                } finally {
                    stream.release()
                }
                return Outcome(TextNormalizer.normalize(finals.toString()), total / 1_000_000, stepMax / 1_000_000)
            }

            override fun close() = recognizer.release()
        }
    }

    private fun offline(config: OfflineRecognizerConfig): Engine {
        val recognizer = OfflineRecognizer(config = config)
        return object : Engine {
            override fun recognize(samples: FloatArray): Outcome {
                val started = System.nanoTime()
                val stream = recognizer.createStream()
                try {
                    stream.acceptWaveform(samples, MicCapture.SAMPLE_RATE)
                    recognizer.decode(stream)
                    val text = recognizer.getResult(stream).text
                    return Outcome(TextNormalizer.normalize(text), (System.nanoTime() - started) / 1_000_000, 0)
                } finally {
                    stream.release()
                }
            }

            override fun close() = recognizer.release()
        }
    }

    /** 16kHz 모노 16비트 WAV 를 -1~1 범위의 값으로 읽는다. */
    private fun read(file: File): FloatArray {
        val bytes = file.readBytes()
        val start = WavSource.dataOffset(bytes)
        return FloatArray((bytes.size - start) / 2) { i ->
            val pos = start + i * 2
            ((bytes[pos].toInt() and 0xff) or (bytes[pos + 1].toInt() shl 8)) / 32768f
        }
    }

    private fun report(message: String): String {
        Log.i(TAG, message)
        status.value = message
        return message
    }

    private companion object {
        const val TAG = "LocalTransBench"
        /** 발화를 이만큼 잰 시점에 실시간보다 느리면, SLOW_LIMIT 개까지만 잰다. */
        const val SLOW_CHECK = 20
        const val SLOW_LIMIT = 50
    }
}
