package dev.localtrans.app.model

import android.content.Context
import dev.localtrans.app.config.LlmConfig
import java.io.File

data class SttFiles(val encoder: File, val decoder: File, val joiner: File, val tokens: File) {
    fun all() = listOf(encoder, decoder, joiner, tokens)
}

/**
 * 모델은 APK에 넣지 않고 앱 전용 외부 폴더에 둔다(scripts/push-models.sh).
 * 앱을 업데이트해도 유지되고, 앱을 삭제하면 함께 지워진다.
 */
class ModelStore(context: Context) {
    val root: File = File(context.getExternalFilesDir(null) ?: context.filesDir, "models").apply {
        // 앱이 먼저 폴더를 만들어 둬야 adb push 로 넣은 파일의 소유권이 꼬이지 않는다.
        File(this, "stt").mkdirs()
    }

    fun llmFile(cfg: LlmConfig) = File(root, cfg.modelFile)

    fun sttFiles(): SttFiles {
        val dir = File(root, "stt")
        return SttFiles(
            encoder = File(dir, "encoder.onnx"),
            decoder = File(dir, "decoder.onnx"),
            joiner = File(dir, "joiner.onnx"),
            tokens = File(dir, "tokens.txt"),
        )
    }

    /** 없는 모델 파일을 models 폴더 기준 상대 경로로 돌려준다. */
    fun missing(cfg: LlmConfig): List<String> =
        (sttFiles().all() + llmFile(cfg))
            .filter { !it.isFile || it.length() == 0L }
            .map { it.relativeTo(root).path }
}
