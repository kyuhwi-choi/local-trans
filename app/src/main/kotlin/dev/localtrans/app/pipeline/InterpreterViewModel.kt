package dev.localtrans.app.pipeline

import android.app.Application
import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.localtrans.app.LocalTransApp
import dev.localtrans.app.BuildConfig
import dev.localtrans.app.audio.EarphoneMonitor
import dev.localtrans.app.audio.MicCapture
import dev.localtrans.app.audio.WavSource
import dev.localtrans.app.config.AppConfig
import dev.localtrans.app.config.LlmConfig
import dev.localtrans.app.config.SttConfig
import dev.localtrans.app.llm.GemmaLlm
import dev.localtrans.app.llm.Prompts
import dev.localtrans.app.llm.Suggestion
import dev.localtrans.app.llm.SuggestionParser
import dev.localtrans.app.stt.SecondPassStt
import dev.localtrans.app.stt.StreamingStt
import dev.localtrans.app.tts.KoreanSpeaker
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class Utterance(
    val id: Long,
    val english: String,
    val korean: String = "",
    val done: Boolean = false,
    /** 구간이 확정된 뒤 번역이 끝날 때까지 걸린 시간 */
    val latencyMs: Long? = null,
)

/** Starting 은 통역 시작을 눌렀지만 모델이 아직 올라가는 중인 상태 */
enum class Phase { Idle, Starting, Listening }

enum class ModelState { Unloaded, Loading, Ready }

enum class Mode {
    /** 화면의 자막으로만 통역한다. */
    Basic,
    /** 자막에 더해, 폰 마이크로 들은 영어의 번역을 이어폰으로 읽어 준다. */
    Bluetooth,
}

data class UiState(
    val phase: Phase = Phase.Idle,
    val models: ModelState = ModelState.Unloaded,
    val mode: Mode = Mode.Basic,
    /** 연결된 이어폰 이름. 없으면 null */
    val earphone: String? = null,
    val voice: KoreanSpeaker.State = KoreanSpeaker.State.Off,
    val modelDir: String = "",
    val missingModels: List<String> = emptyList(),
    val backend: String = "",
    /** 구간을 다시 인식하는 2단계 모델이 올라가 있는지 */
    val secondPass: Boolean = false,
    val partial: String = "",
    val utterances: List<Utterance> = emptyList(),
    val suggestions: List<Suggestion> = emptyList(),
    val suggesting: Boolean = false,
    val error: String? = null,
)

/**
 * 마이크 → 스트리밍 인식 → (2단계 인식) → 구간별 번역 → 답변 제안의 흐름을 묶는다.
 * 2단계 모델이 있으면 자막은 스트리밍 결과로 바로 띄우고, 구간이 끝나면 다시 인식한 문장으로 바꿔 번역한다.
 * 번역이 항상 우선이고, 답변 제안은 상대가 다시 말하기 시작하면 중단한다.
 * 모델은 통역과 따로 올리고 내릴 수 있어서, 미리 올려 두면 통역을 바로 시작할 수 있다.
 */
class InterpreterViewModel(app: Application) : AndroidViewModel(app) {

    private val localApp = app as LocalTransApp
    private val configRepo = localApp.config
    private val models = localApp.models
    val updates = localApp.updates

    private val _ui = MutableStateFlow(UiState(modelDir = models.root.absolutePath))
    val ui = _ui.asStateFlow()

    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val speaker = KoreanSpeaker(app)
    private val earphones = EarphoneMonitor(app)

    // 모델을 올리고 내리는 동안에는 modelLock 을 잡는다.
    private val modelLock = Mutex()
    @Volatile private var stt: StreamingStt? = null
    @Volatile private var second: SecondPassStt? = null
    @Volatile private var sttKey: SttKey? = null
    @Volatile private var llm: GemmaLlm? = null
    @Volatile private var llmKey: LlmConfig? = null

    private var loadJob: Job? = null
    private var session: Job? = null
    private var suggestJob: Job? = null
    private var nextId = 0L

    init {
        val saved = runCatching { Mode.valueOf(prefs.getString(KEY_MODE, null).orEmpty()) }.getOrDefault(Mode.Basic)
        _ui.update { it.copy(mode = saved) }
        if (saved == Mode.Bluetooth) speaker.prepare()
        earphones.start()
        viewModelScope.launch {
            earphones.name.collect { name ->
                DebugTrace.log("earphone: $name")
                _ui.update { it.copy(earphone = name) }
            }
        }
        viewModelScope.launch {
            speaker.state.collect { state ->
                DebugTrace.log("voice: $state")
                _ui.update { it.copy(voice = state) }
            }
        }

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

    /** 통역 중에도 바꿀 수 있다. 마이크 입력 고정만은 다음 통역 시작부터 적용된다. */
    fun setMode(mode: Mode) {
        if (_ui.value.mode == mode) return
        prefs.edit().putString(KEY_MODE, mode.name).apply()
        _ui.update { it.copy(mode = mode) }
        if (mode == Mode.Bluetooth) speaker.prepare() else speaker.stop()
    }

    /** 통역을 시작하기 전에 모델을 미리 올려 둔다. */
    fun loadModels() {
        if (loadJob?.isActive == true) return
        val cfg = configRepo.config.value
        if (!modelsPresent(cfg)) return
        loadJob = viewModelScope.launch {
            try {
                ensureModels(cfg)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                showError("모델을 올리지 못했습니다: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    /** 통역을 멈추고 모델을 메모리에서 내린다. */
    fun unloadModels() {
        val running = session
        stop()
        viewModelScope.launch {
            // 인식·번역이 모델을 쓰는 도중에 닫으면 안 되므로 완전히 끝난 뒤에 내린다.
            running?.join()
            loadJob?.join()
            modelLock.withLock {
                withContext(Dispatchers.Default) { releaseModels() }
                _ui.update { it.copy(models = ModelState.Unloaded, backend = "", secondPass = false) }
            }
        }
    }

    fun start() {
        if (session?.isActive == true) return
        val cfg = configRepo.config.value
        if (!modelsPresent(cfg)) return

        session = viewModelScope.launch {
            try {
                _ui.update { it.copy(phase = Phase.Starting) }
                ensureModels(cfg)
                val recognizer = checkNotNull(stt)
                recognizer.reset()
                _ui.update { it.copy(phase = Phase.Listening) }
                listen(recognizer, cfg)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                showError(t.message ?: t.javaClass.simpleName)
            } finally {
                speaker.stop()
                _ui.update { it.copy(phase = Phase.Idle, partial = "", suggesting = false) }
            }
        }
    }

    /** 통역만 멈춘다. 모델은 올라간 채로 두어 다시 시작할 때 바로 듣는다. */
    fun stop() {
        session?.cancel()
        session = null
    }

    private fun modelsPresent(cfg: AppConfig): Boolean {
        val missing = models.missing(cfg.llm)
        _ui.update { it.copy(missingModels = missing, error = null) }
        return missing.isEmpty()
    }

    private fun sttKeyFor(cfg: AppConfig) = SttKey(cfg.stt, cfg.stt.secondPass && models.hasSecondPass())

    private fun isLoaded(cfg: AppConfig) =
        stt != null && sttKey == sttKeyFor(cfg) && llm != null && llmKey == cfg.llm

    private fun readyState(state: UiState) =
        state.copy(models = ModelState.Ready, backend = llm?.activeBackend.orEmpty(), secondPass = second != null)

    /** 모델이 [cfg] 대로 올라가 있지 않으면 올린다. 이미 올라가 있으면 바로 돌아온다. */
    private suspend fun ensureModels(cfg: AppConfig) = modelLock.withLock {
        if (isLoaded(cfg)) {
            _ui.update { readyState(it) }
            return@withLock
        }
        _ui.update { it.copy(models = ModelState.Loading) }
        try {
            withContext(Dispatchers.Default) { ensureLoaded(cfg) }
        } finally {
            // 올리는 작업은 중간에 끊을 수 없다. 취소되었더라도 끝까지 올라갔으면 준비된 상태로 두고,
            // 실패해서 일부만 올라갔으면 모두 내린다.
            val ready = isLoaded(cfg)
            if (!ready) withContext(NonCancellable + Dispatchers.Default) { releaseModels() }
            _ui.update {
                if (ready) readyState(it) else it.copy(models = ModelState.Unloaded, backend = "", secondPass = false)
            }
        }
    }

    private fun releaseModels() {
        stt?.close()
        stt = null
        second?.close()
        second = null
        sttKey = null
        llm?.close()
        llm = null
        llmKey = null
    }

    private fun ensureLoaded(cfg: AppConfig) {
        val key = sttKeyFor(cfg)
        if (stt == null || sttKey != key) {
            stt?.close()
            stt = null
            second?.close()
            second = null
            stt = StreamingStt(models.sttFiles(), cfg.stt, keepAudio = key.secondPass)
            if (key.secondPass) {
                // 2단계 모델은 없어도 통역이 되므로, 올리지 못하면 스트리밍 인식만으로 계속한다.
                second = runCatching { SecondPassStt(models.secondPassFiles(), cfg.stt) }
                    .onFailure { showError("2단계 인식 모델을 올리지 못해 스트리밍 인식만 씁니다: ${it.message ?: it.javaClass.simpleName}") }
                    .getOrNull()
            }
            sttKey = key
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
        val finals = Channel<Heard>(Channel.UNLIMITED)
        var pending = 0 // 메인 스레드에서만 읽고 쓴다
        val refiner = second
        // 2단계 인식은 구간이 끝나자마자 시작하되, 인식기를 한 번에 한 구간씩만 쓰도록 한 줄로 세운다.
        val refineQueue = Dispatchers.Default.limitedParallelism(1)

        launch {
            for (heard in finals) {
                val started = SystemClock.elapsedRealtime()
                val utterance = refined(heard)
                translate(utterance, cfg, started)
                pending--
                // 밀린 번역이 없을 때만 답변 제안을 만든다.
                if (pending == 0) suggest(this@coroutineScope, utterance, cfg)
            }
        }

        // 디버그 빌드에서 앱 폴더에 debug-input.wav 가 있으면 마이크 대신 그 파일을 입력으로 쓴다.
        val debugInput = models.root.resolveSibling(WavSource.FILE_NAME)
            .takeIf { BuildConfig.DEBUG && it.isFile }
        if (debugInput != null) DebugTrace.log("input: ${debugInput.name}")
        // 블루투스 모드에서는 이어폰 마이크가 아니라 폰 마이크로 듣도록 입력을 고정한다.
        val mic = if (_ui.value.mode == Mode.Bluetooth) earphones.builtInMic() else null
        (debugInput?.let { WavSource.frames(it) } ?: MicCapture.frames(preferred = mic))
            .mapNotNull { audio -> DebugTrace.chunk(audio) { recognizer.accept(audio) } }
            .flowOn(Dispatchers.Default)
            .collect { event ->
                when (event) {
                    is StreamingStt.Event.Partial -> {
                        DebugTrace.log("partial: ${event.text}")
                        if (event.text.isNotEmpty()) cancelSuggest()
                        _ui.update { it.copy(partial = event.text) }
                    }
                    is StreamingStt.Event.Final -> {
                        DebugTrace.log("final: ${event.text}")
                        cancelSuggest()
                        val utterance = Utterance(id = nextId++, english = event.text)
                        _ui.update {
                            it.copy(partial = "", utterances = (it.utterances + utterance).takeLast(MAX_UTTERANCES))
                        }
                        pending++
                        val audio = event.audio
                        val refining = if (refiner != null && audio != null) {
                            async(refineQueue) { secondPass(refiner, audio) }
                        } else {
                            null
                        }
                        finals.send(Heard(utterance, refining))
                    }
                }
            }
    }

    private fun previousEnglish(before: Long, count: Int): List<String> =
        _ui.value.utterances.filter { it.id < before }.takeLast(count).map { it.english }

    /** 2단계 인식 결과. 실패하거나 아무것도 못 들었으면 빈 문자열을 돌려줘 스트리밍 결과를 그대로 쓰게 한다. */
    private fun secondPass(refiner: SecondPassStt, audio: FloatArray): String {
        val started = SystemClock.elapsedRealtime()
        val text = runCatching { refiner.recognize(audio) }.getOrDefault("")
        DebugTrace.log(
            "second pass (${SystemClock.elapsedRealtime() - started} ms, " +
                "${audio.size * 10 / MicCapture.SAMPLE_RATE / 10f} s): $text"
        )
        return text
    }

    /** 2단계 인식이 끝나기를 기다려, 결과가 있으면 화면의 영어 문장을 그것으로 바꾼다. */
    private suspend fun refined(heard: Heard): Utterance {
        val text = heard.refining?.await().orEmpty()
        if (text.isEmpty() || text == heard.utterance.english) return heard.utterance
        updateUtterance(heard.utterance.id) { it.copy(english = text) }
        return heard.utterance.copy(english = text)
    }

    /** [started] 는 구간이 확정되어 처리를 시작한 시각 (2단계 인식 시간도 지연에 넣는다). */
    private suspend fun translate(utterance: Utterance, cfg: AppConfig, started: Long) {
        val engine = llm ?: return
        DebugTrace.log("translate start: ${utterance.english}")
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
            var korean = ""
            engine.generate(request).collect { text ->
                korean = Prompts.cleanTranslation(text)
                updateUtterance(utterance.id) { it.copy(korean = korean) }
            }
            updateUtterance(utterance.id) {
                it.copy(done = true, latencyMs = SystemClock.elapsedRealtime() - started)
            }
            DebugTrace.log("translate end (${SystemClock.elapsedRealtime() - started} ms)")
            speak(korean, cfg)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            updateUtterance(utterance.id) { it.copy(done = true, korean = it.korean.ifEmpty { "(번역 실패)" }) }
            showError("번역 오류: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    /** 블루투스 모드이고 이어폰이 연결되어 있을 때만 번역을 읽는다. 폰 스피커로는 내보내지 않는다. */
    private fun speak(korean: String, cfg: AppConfig) {
        val state = _ui.value
        if (state.mode != Mode.Bluetooth || korean.isBlank()) return
        if (state.earphone == null) {
            DebugTrace.log("speak skipped: no earphone")
            return
        }
        val spoken = speaker.speak(korean, cfg.tts)
        DebugTrace.log("speak (${if (spoken) "queued" else "voice not ready"}): $korean")
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
            val started = SystemClock.elapsedRealtime()
            DebugTrace.log("suggest start")
            try {
                engine.generate(request).collect { raw ->
                    _ui.update { it.copy(suggestions = SuggestionParser.parse(raw, count)) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // 답변 제안 실패는 통역을 멈출 이유가 아니다.
            } finally {
                DebugTrace.log("suggest end (${SystemClock.elapsedRealtime() - started} ms)")
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
        earphones.stop()
        speaker.shutdown()
        stt?.close()
        second?.close()
        llm?.close()
    }

    /** 음성 인식 모델을 다시 올려야 하는지 가리는 값: 설정과, 2단계 모델을 쓰는지 */
    private data class SttKey(val cfg: SttConfig, val secondPass: Boolean)

    /** 확정된 구간과, 진행 중인 2단계 인식(없으면 null) */
    private class Heard(val utterance: Utterance, val refining: Deferred<String>?)

    private companion object {
        const val MAX_UTTERANCES = 200
        const val KEY_MODE = "mode"
    }
}
