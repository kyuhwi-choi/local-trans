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
import dev.localtrans.app.stt.SilenceDetector
import dev.localtrans.app.stt.StreamingStt
import dev.localtrans.app.stt.TextNormalizer
import dev.localtrans.app.tts.KoreanSpeaker
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
    /** 내가 말하는 차례라 듣기를 잠시 멈춘 상태 (통역 중에만 의미가 있다) */
    val paused: Boolean = false,
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
    /** 통역할 때 들은 음성과 결과를 파일로 남길지 (디버그 빌드에서만 쓴다) */
    val recording: Boolean = false,
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
 * 통역하는 동안에는 포그라운드 서비스(ListeningService)를 띄워, 화면이 꺼져도 계속 듣는다.
 * 모델은 통역과 따로 올리고 내릴 수 있어서, 미리 올려 두면 통역을 바로 시작할 수 있다.
 */
class InterpreterViewModel(app: Application) : AndroidViewModel(app) {

    private val localApp = app as LocalTransApp
    private val configRepo = localApp.config
    private val models = localApp.models
    private val link = localApp.session
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
    @Volatile private var recorder: SessionRecorder? = null
    private var nextId = 0L

    init {
        val saved = runCatching { Mode.valueOf(prefs.getString(KEY_MODE, null).orEmpty()) }.getOrDefault(Mode.Basic)
        _ui.update { it.copy(mode = saved, recording = BuildConfig.DEBUG && prefs.getBoolean(KEY_RECORDING, false)) }
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

        // 알림에 보일 통역 상태를 맞추고, 알림의 버튼(내 차례, 중지)을 받는다.
        viewModelScope.launch {
            ui.collect { link.status.value = SessionLink.statusOf(it.phase, it.paused) }
        }
        viewModelScope.launch {
            link.commands.collect { command ->
                when (command) {
                    SessionLink.Command.Stop -> stop()
                    SessionLink.Command.TogglePause -> setPaused(!_ui.value.paused)
                }
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

    /** 다음 통역부터 적용된다. */
    fun setRecording(recording: Boolean) {
        prefs.edit().putBoolean(KEY_RECORDING, recording).apply()
        _ui.update { it.copy(recording = recording) }
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

    /** [sessionName] 은 통역 기록 파일의 이름 (디버그 빌드의 벤치마크용). 없으면 시작한 시각으로 짓는다. */
    fun start(sessionName: String? = null) {
        if (session?.isActive == true) return
        val cfg = configRepo.config.value
        if (!modelsPresent(cfg)) return

        session = viewModelScope.launch {
            val job = coroutineContext.job
            try {
                _ui.update { it.copy(phase = Phase.Starting, paused = false) }
                // 모델을 올리는 동안 앱을 벗어나도 이어지도록, 화면이 보이는 지금 서비스를 띄운다.
                ListeningService.start(getApplication())
                ensureModels(cfg)
                val recognizer = checkNotNull(stt)
                recognizer.reset()
                _ui.update { it.copy(phase = Phase.Listening) }
                listen(recognizer, cfg, sessionName)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                showError(t.message ?: t.javaClass.simpleName)
            } finally {
                // 멈춘 직후 다시 시작했다면 새 통역이 이미 돌고 있으므로 건드리지 않는다.
                val current = session
                if (current == null || current === job) {
                    speaker.stop()
                    ListeningService.stop(getApplication())
                    _ui.update { it.copy(phase = Phase.Idle, paused = false, partial = "", suggesting = false) }
                }
            }
        }
    }

    /**
     * 내가 말할 차례에 듣기를 잠시 멈춘다. 멈춘 동안 들어온 소리는 인식하지 않아서,
     * 내가 한 말이 번역되거나 이어폰으로 읽히지 않는다. 이미 들은 말의 번역과 답변 제안은 그대로 이어진다.
     */
    fun setPaused(paused: Boolean) {
        val state = _ui.value
        if (state.phase != Phase.Listening || state.paused == paused) return
        DebugTrace.log(if (paused) "paused" else "resumed")
        recorder?.event("pause") { put("paused", paused) }
        _ui.update { it.copy(paused = paused) }
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
            // 미리 번역은 2단계 인식에 넘길 음성이 있어야 할 수 있다. VAD 를 올리지 못하면 미리 번역만 빠진다.
            val silence = if (key.secondPass && cfg.stt.speculateSilenceSec > 0f) {
                runCatching { SilenceDetector(getApplication<Application>().assets) }
                    .onFailure { DebugTrace.log("vad failed: $it") }
                    .getOrNull()
            } else {
                null
            }
            stt = try {
                StreamingStt(models.sttFiles(), cfg.stt, keepAudio = key.secondPass, silence = silence)
            } catch (t: Throwable) {
                silence?.close()
                throw t
            }
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

    private suspend fun listen(recognizer: StreamingStt, cfg: AppConfig, sessionName: String?) {
        // 디버그 빌드에서 앱 폴더에 debug-input.wav 가 있으면 마이크 대신 그 파일을 입력으로 쓴다.
        val debugInput = models.root.resolveSibling(WavSource.FILE_NAME)
            .takeIf { BuildConfig.DEBUG && it.isFile }
        if (debugInput != null) DebugTrace.log("input: ${debugInput.name}")
        val log = openRecorder(sessionName, cfg, fromFile = debugInput != null)
        recorder = log
        try {
            listen(recognizer, cfg, debugInput, log)
        } finally {
            recorder = null
            log?.close()
        }
    }

    private suspend fun listen(
        recognizer: StreamingStt,
        cfg: AppConfig,
        debugInput: File?,
        log: SessionRecorder?,
    ) = coroutineScope {
        val finals = Channel<Heard>(Channel.UNLIMITED)
        var pending = 0 // 메인 스레드에서만 읽고 쓴다
        var spec: Speculation? = null // 메인 스레드에서만 읽고 쓴다
        val refiner = second
        // 2단계 인식은 구간이 끝나자마자 시작하되, 인식기를 한 번에 한 구간씩만 쓰도록 한 줄로 세운다.
        val refineQueue = Dispatchers.Default.limitedParallelism(1)

        launch {
            for (heard in finals) {
                val started = SystemClock.elapsedRealtime()
                val done = process(heard, cfg, started)
                val utterance = done.utterance
                log?.event("utterance") {
                    put("id", utterance.id)
                    put("finalMs", heard.finalMs)
                    put("audioSec", heard.audioSec)
                    put("speculated", done.speculated)
                    put("streaming", heard.utterance.english)
                    put("english", utterance.english)
                    put("korean", done.korean.orEmpty())
                }
                pending--
                // 밀린 번역이 없을 때만 답변 제안을 만든다.
                if (pending == 0) suggest(this@coroutineScope, utterance, cfg)
            }
        }

        // 블루투스 모드에서는 이어폰 마이크가 아니라 폰 마이크로 듣도록 입력을 고정한다.
        val mic = if (_ui.value.mode == Mode.Bluetooth) earphones.builtInMic() else null
        var wasPaused = false // 인식하는 쪽에서만 읽고 쓴다
        (debugInput?.let { WavSource.frames(it) } ?: MicCapture.frames(preferred = mic))
            .mapNotNull { audio ->
                val paused = _ui.value.paused
                // 내가 말하는 동안의 소리는 남기지 않되, 음성 속 시각이 어긋나지 않게 무음으로 채운다.
                log?.audio(if (paused) FloatArray(audio.size) else audio)
                when {
                    // 멈추는 순간 듣고 있던 구간을 바로 확정해, 상대가 방금 한 말이 버려지지 않게 한다.
                    paused && !wasPaused -> {
                        wasPaused = true
                        recognizer.flush()
                    }
                    paused -> null
                    else -> {
                        wasPaused = false
                        DebugTrace.chunk(audio) { recognizer.accept(audio) }
                    }
                }
            }
            .flowOn(Dispatchers.Default)
            .collect { event ->
                when (event) {
                    is StreamingStt.Event.Partial -> {
                        DebugTrace.log("partial: ${event.text}")
                        if (event.text.isNotEmpty()) cancelSuggest()
                        _ui.update { it.copy(partial = event.text) }
                    }
                    is StreamingStt.Event.Tentative -> {
                        spec?.cancel()
                        // 번역이 밀려 있으면 어차피 바로 시작할 수 없으므로 미리 하지 않는다.
                        spec = if (refiner != null && pending == 0) {
                            DebugTrace.log("speculate: ${seconds(event.audio)} s")
                            speculate(this, refiner, event.audio, cfg, refineQueue)
                        } else {
                            null
                        }
                    }
                    StreamingStt.Event.Discard -> {
                        if (spec != null) DebugTrace.log("speculate dropped")
                        spec?.cancel()
                        spec = null
                    }
                    is StreamingStt.Event.Final -> {
                        // 미리 시작한 뒤로 말소리가 더 없었으면 그 번역을 이어 쓸 후보로 넘긴다.
                        val held = spec.takeIf { event.speculated }
                        if (held == null) spec?.cancel()
                        spec = null
                        DebugTrace.log("final${if (held != null) " (speculated)" else ""}: ${event.text}")
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
                        finals.send(
                            Heard(
                                utterance = utterance,
                                refining = refining,
                                spec = held,
                                finalMs = log?.now() ?: 0,
                                audioSec = audio?.let { seconds(it) } ?: 0f,
                            )
                        )
                    }
                }
            }
        // 파일 입력이 끝났을 때만 여기에 온다. 밀린 번역과 답변 제안을 마치면 통역이 끝난다.
        spec?.cancel()
        finals.close()
    }

    /** 디버그 빌드에서, 음성 저장을 켰거나 파일을 입력으로 쓸 때 통역 기록을 남긴다. */
    private fun openRecorder(sessionName: String?, cfg: AppConfig, fromFile: Boolean): SessionRecorder? {
        if (!BuildConfig.DEBUG) return null
        val recordAudio = _ui.value.recording && !fromFile
        if (!recordAudio && !fromFile) return null
        return runCatching {
            val dir = models.root.resolveSibling(RECORDINGS_DIR).apply { mkdirs() }
            val name = sessionName?.takeIf { SESSION_NAME.matches(it) }
                ?: SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val header = buildJsonObject {
                put("type", "session")
                put("started", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date()))
                put("input", if (fromFile) "file" else "mic")
                put("app", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                put("mode", _ui.value.mode.name)
                put("backend", llm?.activeBackend.orEmpty())
                put("secondPass", second != null)
                put("stt", sessionJson.encodeToJsonElement(SttConfig.serializer(), cfg.stt))
            }
            SessionRecorder(dir, name, recordAudio, header).also { DebugTrace.log("recording: $name") }
        }.onFailure {
            showError("통역 기록을 남기지 못합니다: ${it.message ?: it.javaClass.simpleName}")
        }.getOrNull()
    }

    private fun seconds(audio: FloatArray): Float = audio.size * 10 / MicCapture.SAMPLE_RATE / 10f

    private fun previousEnglish(before: Long, count: Int): List<String> =
        _ui.value.utterances.filter { it.id < before }.takeLast(count).map { it.english }

    /** 2단계 인식 결과. 실패하거나 아무것도 못 들었으면 빈 문자열을 돌려줘 스트리밍 결과를 그대로 쓰게 한다. */
    private fun secondPass(refiner: SecondPassStt, audio: FloatArray): String {
        val started = SystemClock.elapsedRealtime()
        val text = runCatching { refiner.recognize(audio) }.getOrDefault("")
        DebugTrace.log("second pass (${SystemClock.elapsedRealtime() - started} ms, ${seconds(audio)} s): $text")
        return text
    }

    /**
     * 말이 잠깐 멈췄을 때, 구간이 끝나기를 기다리지 않고 지금까지의 음성으로 2단계 인식과 번역을 시작한다.
     * 화면에는 아직 보이지 않고, 그대로 구간이 끝나면 [process] 가 확인한 뒤 이어받는다.
     */
    private fun speculate(
        scope: CoroutineScope,
        refiner: SecondPassStt,
        audio: FloatArray,
        cfg: AppConfig,
        refineQueue: CoroutineDispatcher,
    ): Speculation {
        val english = scope.async(refineQueue) { secondPass(refiner, audio) }
        val progress = MutableStateFlow("")
        val previous = _ui.value.utterances.takeLast(cfg.llm.contextUtterances).map { it.english }
        val korean = scope.async {
            val engine = llm ?: return@async null
            val text = english.await()
            if (text.isEmpty()) return@async null
            cancelSuggest()
            try {
                generateKorean(engine, text, previous, cfg) { progress.value = it }.ifBlank { null }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // 미리 한 번역이 실패하면 구간이 끝난 뒤 평소대로 다시 번역한다.
                null
            }
        }
        return Speculation(english, korean, progress)
    }

    /** 확정된 구간 하나를 번역까지 마친다. */
    private suspend fun process(heard: Heard, cfg: AppConfig, started: Long): Processed {
        val full = heard.refining?.await().orEmpty()
        val utterance = withEnglish(heard.utterance, full)
        val spec = heard.spec
        if (spec != null) {
            // 미리 한 번역은 그때까지의 음성으로 한 것이다. 구간 전체를 다시 인식한 문장과 같은 말일 때만 이어 쓴다
            // (작은 목소리로 이어진 말을 놓쳐 문장이 잘렸을 수 있다).
            val korean = if (TextNormalizer.sameWords(spec.english.await(), full)) follow(utterance.id, spec) else null
            if (korean != null) {
                finish(utterance.id, korean, started, cfg)
                return Processed(utterance, korean, speculated = true)
            }
            DebugTrace.log("speculation not used")
            spec.cancel()
        }
        return Processed(utterance, translate(utterance, cfg, started), speculated = false)
    }

    /** 미리 시작한 번역이 만들어 내는 글을 화면에 따라 적고, 끝나면 번역문(실패했으면 null)을 돌려준다. */
    private suspend fun follow(id: Long, spec: Speculation): String? = coroutineScope {
        val mirror = launch {
            spec.progress.collect { text ->
                if (text.isNotEmpty()) updateUtterance(id) { it.copy(korean = text) }
            }
        }
        try {
            spec.korean.await()
        } finally {
            mirror.cancel()
        }
    }

    /** 2단계 인식 결과가 있으면 화면의 영어 문장을 그것으로 바꾼다. */
    private fun withEnglish(utterance: Utterance, text: String): Utterance {
        if (text.isEmpty() || text == utterance.english) return utterance
        updateUtterance(utterance.id) { it.copy(english = text) }
        return utterance.copy(english = text)
    }

    /** 번역을 만들면서 지금까지의 번역문을 [onText] 로 알리고, 끝나면 전체를 돌려준다. */
    private suspend fun generateKorean(
        engine: GemmaLlm,
        english: String,
        previous: List<String>,
        cfg: AppConfig,
        onText: (String) -> Unit,
    ): String {
        DebugTrace.log("translate start: $english")
        val request = GemmaLlm.Request(
            system = cfg.prompts.translateSystem,
            user = Prompts.translateUser(cfg.prompts, english, previous),
            maxTokens = cfg.llm.translateMaxTokens,
            temperature = cfg.llm.translateTemperature,
        )
        var korean = ""
        engine.generate(request).collect { text ->
            korean = Prompts.cleanTranslation(text)
            onText(korean)
        }
        return korean
    }

    /** [started] 는 구간이 확정되어 처리를 시작한 시각 (2단계 인식 시간도 지연에 넣는다). 실패하면 null 을 돌려준다. */
    private suspend fun translate(utterance: Utterance, cfg: AppConfig, started: Long): String? {
        val engine = llm ?: return null
        val previous = previousEnglish(utterance.id, cfg.llm.contextUtterances)
        try {
            val korean = generateKorean(engine, utterance.english, previous, cfg) { text ->
                updateUtterance(utterance.id) { it.copy(korean = text) }
            }
            finish(utterance.id, korean, started, cfg)
            return korean
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            updateUtterance(utterance.id) { it.copy(done = true, korean = it.korean.ifEmpty { "(번역 실패)" }) }
            showError("번역 오류: ${t.message ?: t.javaClass.simpleName}")
            return null
        }
    }

    private fun finish(id: Long, korean: String, started: Long, cfg: AppConfig) {
        updateUtterance(id) {
            it.copy(korean = korean, done = true, latencyMs = SystemClock.elapsedRealtime() - started)
        }
        DebugTrace.log("translate end (${SystemClock.elapsedRealtime() - started} ms)")
        speak(korean, cfg)
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
        ListeningService.stop(getApplication())
        link.status.value = SessionLink.Status.Off
        earphones.stop()
        speaker.shutdown()
        stt?.close()
        second?.close()
        llm?.close()
    }

    /** 음성 인식 모델을 다시 올려야 하는지 가리는 값: 설정과, 2단계 모델을 쓰는지 */
    private data class SttKey(val cfg: SttConfig, val secondPass: Boolean)

    /**
     * 확정된 구간. [refining] 은 진행 중인 2단계 인식(없으면 null), [spec] 은 구간이 끝나기 전에 미리 시작해 둔
     * 인식과 번역(없으면 null). [finalMs] 는 확정된 시각(음성이 시작된 뒤로 흐른 ms, 기록용).
     */
    private class Heard(
        val utterance: Utterance,
        val refining: Deferred<String>?,
        val spec: Speculation?,
        val finalMs: Long,
        val audioSec: Float,
    )

    /** 번역까지 마친 구간: 화면에 남은 문장, 번역문(실패하면 null), 미리 한 번역을 이어 썼는지 */
    private class Processed(val utterance: Utterance, val korean: String?, val speculated: Boolean)

    /** 미리 시작한 2단계 인식([english])과 번역([korean], 실패하면 null). [progress] 는 지금까지 만들어진 번역문 */
    private class Speculation(
        val english: Deferred<String>,
        val korean: Deferred<String?>,
        val progress: StateFlow<String>,
    ) {
        fun cancel() {
            english.cancel()
            korean.cancel()
        }
    }

    private companion object {
        const val MAX_UTTERANCES = 200
        const val KEY_MODE = "mode"
        const val KEY_RECORDING = "recording"
        const val RECORDINGS_DIR = "recordings"
        val SESSION_NAME = Regex("[A-Za-z0-9_-]{1,40}")
        val sessionJson = Json { encodeDefaults = true }
    }
}
