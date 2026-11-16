package dev.localtrans.app.llm

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import dev.localtrans.app.config.LlmConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** LiteRT-LM 으로 Gemma 4 E2B 를 돌린다. 한 번에 한 요청만 처리한다. */
class GemmaLlm(
    private val modelPath: String,
    private val cacheDir: String,
    private val nativeLibraryDir: String,
    private val cfg: LlmConfig,
) : AutoCloseable {

    data class Request(val system: String, val user: String, val maxTokens: Int, val temperature: Float)

    private var engine: Engine? = null
    private val mutex = Mutex()

    /** 실제로 로드된 백엔드 (gpu/npu/cpu) */
    var activeBackend: String = ""
        private set

    /** 모델을 메모리에 올린다. 수 초 이상 걸리므로 메인 스레드 밖에서 호출한다. */
    fun load() {
        var lastError: Throwable? = null
        for (name in listOf(cfg.backend.lowercase(), "cpu").distinct()) {
            try {
                val candidate = Engine(
                    EngineConfig(modelPath = modelPath, backend = backendOf(name), cacheDir = cacheDir)
                )
                candidate.initialize()
                engine = candidate
                activeBackend = name
                return
            } catch (t: Throwable) {
                lastError = t
            }
        }
        throw IllegalStateException("LLM 로드 실패: ${lastError?.message}", lastError)
    }

    private fun backendOf(name: String): Backend = when (name) {
        "gpu" -> Backend.GPU()
        "npu" -> Backend.NPU(nativeLibraryDir)
        else -> Backend.CPU()
    }

    /**
     * 응답을 생성하면서 "지금까지의 전체 텍스트"를 계속 내보낸다.
     * 수집을 취소하면 생성도 중단한다. 요청마다 새 대화를 만들어 이전 요청의 문맥이 섞이지 않게 한다.
     */
    fun generate(request: Request): Flow<String> = flow {
        val engine = checkNotNull(engine) { "LLM이 로드되지 않았습니다" }
        mutex.withLock {
            val conversation = engine.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(request.system),
                    samplerConfig = SamplerConfig(
                        topK = cfg.topK,
                        topP = cfg.topP.toDouble(),
                        temperature = request.temperature.toDouble(),
                    ),
                    maxOutputToken = request.maxTokens,
                    // thinking 이 켜지면 소형 모델이 크게 느려지므로 끈다.
                    thinkingConfig = ThinkingConfig(enableThinking = false),
                )
            )
            var finished = false
            try {
                val text = StringBuilder()
                conversation.sendMessageAsync(request.user).collect { chunk ->
                    text.append(chunk.toString())
                    emit(text.toString())
                }
                finished = true
            } finally {
                if (!finished) runCatching { conversation.cancelProcess() }
                conversation.close()
            }
        }
    }.flowOn(Dispatchers.Default)

    override fun close() {
        engine?.close()
        engine = null
    }
}
