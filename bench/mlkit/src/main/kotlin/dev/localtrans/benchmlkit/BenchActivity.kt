package dev.localtrans.benchmlkit

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.TextView
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import java.io.File
import kotlin.concurrent.thread
import org.json.JSONObject

/**
 * ML Kit 온디바이스 번역(영→한) 벤치마크. bench/run.sh 가 띄운다.
 * files/bench/input.jsonl 의 문장을 번역해 files/bench/output-mlkit.jsonl 에 결과와 소요 시간을 적고,
 * 끝나면 done-mlkit.json 을 남긴다. 한국어 모델(약 30MB)은 처음 한 번만 내려받는다.
 */
class BenchActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        status = TextView(this).apply {
            textSize = 18f
            setPadding(48, 160, 48, 48)
        }
        setContentView(status)
        thread(name = "bench") { run() }
    }

    private fun run() {
        val dir = File(getExternalFilesDir(null), "bench").apply { mkdirs() }
        val input = File(dir, "input.jsonl")
        val output = File(dir, "output-mlkit.jsonl")
        val done = File(dir, "done-mlkit.json")
        output.delete()
        done.delete()
        if (!input.isFile) {
            report("입력 파일이 없습니다: $input")
            return
        }
        try {
            val translator = Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(TranslateLanguage.ENGLISH)
                    .setTargetLanguage(TranslateLanguage.KOREAN)
                    .build()
            )
            report("번역 모델 확인 중 (처음에는 내려받습니다)")
            val loadStarted = System.nanoTime()
            Tasks.await(translator.downloadModelIfNeeded(DownloadConditions.Builder().build()))
            // 첫 요청은 모델을 메모리에 올리느라 느리므로 재지 않는다.
            Tasks.await(translator.translate("Hello, how are you?"))
            val loadMs = (System.nanoTime() - loadStarted) / 1_000_000

            val lines = input.readLines().filter { it.isNotBlank() }
            output.bufferedWriter().use { writer ->
                lines.forEachIndexed { index, line ->
                    val item = JSONObject(line)
                    val started = System.nanoTime()
                    val korean = Tasks.await(translator.translate(item.getString("en")))
                    val ms = (System.nanoTime() - started) / 1_000_000
                    writer.write(JSONObject().put("id", item.getString("id")).put("out", korean).put("ms", ms).toString())
                    writer.newLine()
                    if (index % 20 == 19) report("ML Kit ${index + 1}/${lines.size}")
                }
            }
            translator.close()
            done.writeText(
                JSONObject().put("engine", "mlkit").put("count", lines.size).put("load_ms", loadMs).toString()
            )
            report("끝 (${lines.size}문장)")
        } catch (t: Throwable) {
            Log.e(TAG, "bench failed", t)
            done.writeText(JSONObject().put("error", t.toString()).toString())
            report("실패: $t")
        }
    }

    private fun report(message: String) {
        Log.i(TAG, message)
        runOnUiThread { status.text = message }
    }

    private companion object {
        const val TAG = "LocalTransBench"
    }
}
