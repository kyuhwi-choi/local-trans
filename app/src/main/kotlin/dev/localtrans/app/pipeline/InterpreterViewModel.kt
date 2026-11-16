package dev.localtrans.app.pipeline

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.localtrans.app.LocalTransApp
import dev.localtrans.app.audio.MicCapture
import dev.localtrans.app.config.AppConfig
import dev.localtrans.app.config.LlmConfig
import dev.localtrans.app.config.SttConfig
import dev.localtrans.app.llm.GemmaLlm
import dev.localtrans.app.llm.Prompts
import dev.localtrans.app.llm.Suggestion
import dev.localtrans.app.llm.SuggestionParser
import dev.localtrans.app.stt.StreamingStt
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class Utterance(
    val id: Long,
    val english: String,
    val korean: String = "",
    val done: Boolean = false,
    /** 구간이 확정된 뒤 번역이 끝날 때까지 걸린 시간 */
    val latencyMs: Long? = null,
)

enum class Phase { Idle, Loading, Listening }

data class UiState(
    val phase: Phase = Phase.Idle,
    val modelDir: String = "",
    val missingModels: List<String> = emptyList(),
    val backend: String = "",
    val partial: String = "",
    val utterances: List<Utterance> = emptyList(),
    val suggestions: List<Suggestion> = emptyList(),
    val suggesting: Boolean = false,
    val error: String? = null,
)

/**
 * 마이크 → 스트리밍 인식 → 구간별 번역 → 답변 제안의 흐름을 묶는다.
 * 번역이 항상 우선이고, 답변 제안은 상대가 다시 말하기 시작하면 중단한다.
 */
class InterpreterViewModel(app: Application) : AndroidViewModel(app) {

    private val localApp = app as LocalTransApp
    private val configRepo = localApp.config
    private val models = localApp.models
    val updates = localApp.updates

    private val _ui = MutableStateFlow(UiState(modelDir = models.root.absolutePath))
    val ui = _ui.asStateFlow()

    private var stt: StreamingStt? = null
    private var sttKey: SttConfig? = null
    private var llm: GemmaLlm? = null
    private var llmKey: LlmConfig? = null

    private var session: Job? = null
    private var suggestJob: Job? = null
    private var nextId = 0L

    init {
        refreshModelStatus()
        viewModelScope.launch { if (configRepo.refresh()) refreshModelStatus() }
        viewModelScope.launch { updates.check() }
    }

    fun refreshModelStatus() {
        _ui.update { it.copy(missingModels = models.missing(configRepo.config.value.llm)) }
    }

    fun checkForUpdate() {
        viewModelScope.launch { updates.check() }
    }

    /** 설치가 끝나면 앱이 종료되므로 통역을 먼저 멈춘다. */
    fun installUpdate() {
        stop()
        viewModelScope.launch { updates.downloadAndInstall() }
    }

    fun showError(message: String?) {
        _ui.update { it.copy(error = message) }
    }

    fun start() {
        if (session?.isActive == true) return
        val cfg = configRepo.config.value
        val missing = models.missing(cfg.llm)
        _ui.update { it.copy(missingModels = missing, error = null) }
        if (missing.isNotEmpty()) return

        session = viewModelScope.launch {
            try {
                _ui.update { it.copy(phase = Phase.Loading) }
                withContext(Dispatchers.Default) { ensureLoaded(cfg) }
                val recognizer = checkNotNull(stt)
                recognizer.reset()
                _ui.update { it.copy(phase = Phase.Listening, backend = llm?.activeBackend.orEmpty()) }
                listen(recognizer, cfg)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                showError(t.message ?: t.javaClass.simpleName)
            } finally {
                _ui.update { it.copy(phase = Phase.Idle, partial = "", suggesting = false) }
            }
        }
    }

    fun stop() {
        session?.cancel()
        session = null
    }

    private fun ensureLoaded(cfg: AppConfig) {
        if (stt == null || sttKey != cfg.stt) {
            stt?.close()
            stt = null
            stt = StreamingStt(models.sttFiles(), cfg.stt)
            sttKey = cfg.stt
        }
        if (llm == null || llmKey != cfg.llm) {
            llm?.close()
            llm = null
            val app = getApplication<Application>()
            val engine = GemmaLlm(
                modelPath = models.llmFile(cfg.llm).absolutePath,
                cacheDir = app.cacheDir.absolutePath,
                nativeLibraryDir = app.applicationInfo.nativeLibraryDir,
                cfg = cfg.llm,
            )
            engine.load()
            llm = engine
            llmKey = cfg.llm
        }
    }

    private suspend fun listen(recognizer: StreamingStt, cfg: AppConfig) = coroutineScope {
        val finals = Channel<Utterance>(Channel.UNLIMITED)
        var pending = 0 // 메인 스레드에서만 읽고 쓴다

        launch {
            for (utterance in finals) {
                translate(utterance, cfg)
                pending--
                // 밀린 번역이 없을 때만 답변 제안을 만든다.
                if (pending == 0) suggest(this@coroutineScope, utterance, cfg)
            }
        }

        MicCapture.frames()
            .mapNotNull { recognizer.accept(it) }
            .flowOn(Dispatchers.Default)
            .collect { event ->
                when (event) {
                    is StreamingStt.Event.Partial -> {
                        if (event.text.isNotEmpty()) cancelSuggest()
                        _ui.update { it.copy(partial = event.text) }
                    }
                    is StreamingStt.Event.Final -> {
                        cancelSuggest()
                        val utterance = Utterance(id = nextId++, english = event.text)
                        _ui.update {
                            it.copy(partial = "", utterances = (it.utterances + utterance).takeLast(MAX_UTTERANCES))
                        }
                        pending++
                        finals.send(utterance)
                    }
                }
            }
    }

    private fun previousEnglish(before: Long, count: Int): List<String> =
        _ui.value.utterances.filter { it.id < before }.takeLast(count).map { it.english }

    private suspend fun translate(utterance: Utterance, cfg: AppConfig) {
        val engine = llm ?: return
        val started = SystemClock.elapsedRealtime()
        val request = GemmaLlm.Request(
            system = cfg.prompts.translateSystem,
            user = Prompts.translateUser(
                cfg.prompts,
                utterance.english,
                previousEnglish(utterance.id, cfg.llm.contextUtterances),
            ),
            maxTokens = cfg.llm.translateMaxTokens,
            temperature = cfg.llm.translateTemperature,
        )
        try {
            engine.generate(request).collect { text ->
                updateUtterance(utterance.id) { it.copy(korean = Prompts.cleanTranslation(text)) }
            }
            updateUtterance(utterance.id) {
                it.copy(done = true, latencyMs = SystemClock.elapsedRealtime() - started)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            updateUtterance(utterance.id) { it.copy(done = true, korean = it.korean.ifEmpty { "(번역 실패)" }) }
            showError("번역 오류: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun suggest(scope: CoroutineScope, utterance: Utterance, cfg: AppConfig) {
        val engine = llm ?: return
        val count = cfg.llm.suggestionCount
        if (count <= 0) return
        val request = GemmaLlm.Request(
            system = Prompts.suggestSystem(cfg.prompts, count),
            user = Prompts.suggestUser(
                cfg.prompts,
                utterance.english,
                previousEnglish(utterance.id, cfg.llm.contextUtterances),
            ),
            maxTokens = cfg.llm.suggestMaxTokens,
            temperature = cfg.llm.suggestTemperature,
        )
        suggestJob?.cancel()
        suggestJob = scope.launch {
            _ui.update { it.copy(suggesting = true, suggestions = emptyList()) }
            try {
                engine.generate(request).collect { raw ->
                    _ui.update { it.copy(suggestions = SuggestionParser.parse(raw, count)) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // 답변 제안 실패는 통역을 멈출 이유가 아니다.
            } finally {
                _ui.update { it.copy(suggesting = false) }
            }
        }
    }

    private fun cancelSuggest() {
        suggestJob?.cancel()
        suggestJob = null
    }

    private fun updateUtterance(id: Long, change: (Utterance) -> Utterance) {
        _ui.update { state ->
            state.copy(utterances = state.utterances.map { if (it.id == id) change(it) else it })
        }
    }

    override fun onCleared() {
        session?.cancel()
        stt?.close()
        llm?.close()
    }

    private companion object {
        const val MAX_UTTERANCES = 200
    }
}
