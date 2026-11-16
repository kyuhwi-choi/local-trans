package dev.localtrans.app.bench

import android.os.Bundle
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
import dev.localtrans.app.LocalTransApp
import dev.localtrans.app.config.AppConfig
import dev.localtrans.app.llm.GemmaLlm
import dev.localtrans.app.llm.Prompts
import dev.localtrans.app.ui.LocalTransTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 번역 벤치마크 실행기 (디버그 빌드 전용, bench/run.sh 가 띄운다).
 * files/bench/input.jsonl 의 문장을 앱과 같은 설정·프롬프트로 번역해
 * files/bench/output-gemma.jsonl 에 결과와 소요 시간을 적고, 끝나면 done-gemma.json 을 남긴다.
 */
class BenchActivity : ComponentActivity() {

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

    private suspend fun run(): String {
        val app = application as LocalTransApp
        val dir = File(getExternalFilesDir(null), "bench").apply { mkdirs() }
        val input = File(dir, "input.jsonl")
        val output = File(dir, "output-gemma.jsonl")
        val done = File(dir, "done-gemma.json")
        output.delete()
        done.delete()
        if (!input.isFile) return "입력 파일이 없습니다: $input"

        return try {
            val cfg = app.config.config.value
            val modelFile = app.models.llmFile(cfg.llm)
            val llm = GemmaLlm(
                modelPath = modelFile.absolutePath,
                cacheDir = cacheDir.absolutePath,
                nativeLibraryDir = applicationInfo.nativeLibraryDir,
                cfg = cfg.llm,
            )
            report("모델 올리는 중")
            val loadStarted = System.nanoTime()
            llm.load()
            val loadMs = (System.nanoTime() - loadStarted) / 1_000_000

            // 첫 요청은 준비 작업 때문에 느리므로 재지 않는다.
            translate(llm, cfg, "Hello, how are you?", emptyList())

            val lines = input.readLines().filter { it.isNotBlank() }
            output.bufferedWriter().use { writer ->
                lines.forEachIndexed { index, line ->
                    val item = Json.parseToJsonElement(line).jsonObject
                    val id = item.getValue("id").jsonPrimitive.content
                    val english = item.getValue("en").jsonPrimitive.content
                    val context = item["context"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
                    val result = translate(llm, cfg, english, context)
                    writer.write(
                        buildJsonObject {
                            put("id", id)
                            put("out", result.text)
                            put("ms", result.totalMs)
                            put("first_ms", result.firstMs)
                        }.toString()
                    )
                    writer.newLine()
                    writer.flush()
                    report("Gemma ${index + 1}/${lines.size}")
                }
            }
            llm.close()
            done.writeText(
                buildJsonObject {
                    put("engine", "gemma")
                    put("count", lines.size)
                    put("backend", llm.activeBackend)
                    put("model", modelFile.name)
                    put("model_bytes", modelFile.length())
                    put("load_ms", loadMs)
                    put("translate_max_tokens", cfg.llm.translateMaxTokens)
                }.toString()
            )
            report("끝 (${lines.size}문장)")
        } catch (t: Throwable) {
            Log.e(TAG, "bench failed", t)
            done.writeText(buildJsonObject { put("error", t.toString()) }.toString())
            report("실패: $t")
        }
    }

    private class Result(val text: String, val totalMs: Long, val firstMs: Long)

    /** 앱의 번역과 같은 요청을 보내고, 첫 글자가 나올 때까지와 끝날 때까지의 시간을 잰다. */
    private suspend fun translate(llm: GemmaLlm, cfg: AppConfig, english: String, context: List<String>): Result {
        val request = GemmaLlm.Request(
            system = cfg.prompts.translateSystem,
            user = Prompts.translateUser(cfg.prompts, english, context),
            maxTokens = cfg.llm.translateMaxTokens,
            temperature = cfg.llm.translateTemperature,
        )
        val started = System.nanoTime()
        var first = -1L
        var text = ""
        llm.generate(request).collect {
            if (first < 0) first = (System.nanoTime() - started) / 1_000_000
            text = it
        }
        return Result(Prompts.cleanTranslation(text), (System.nanoTime() - started) / 1_000_000, first)
    }

    private fun report(message: String): String {
        Log.i(TAG, message)
        status.value = message
        return message
    }

    private companion object {
        const val TAG = "LocalTransBench"
    }
}
