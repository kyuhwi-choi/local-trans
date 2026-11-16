package dev.localtrans.app.tts

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dev.localtrans.app.config.TtsConfig
import java.util.Collections
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 번역된 한국어를 기기의 음성 합성(TTS)으로 읽어 준다. 블루투스 모드에서만 쓴다.
 * 소리는 미디어 출력으로 나가므로 이어폰이 연결되어 있으면 이어폰으로 들린다.
 */
class KoreanSpeaker(context: Context) {

    sealed interface State {
        data object Off : State
        data object Preparing : State
        data object Ready : State
        data class Unavailable(val reason: String) : State
    }

    private val appContext = context.applicationContext
    private val _state = MutableStateFlow<State>(State.Off)
    val state = _state.asStateFlow()

    private var tts: TextToSpeech? = null
    private val pending: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())
    private var nextId = 0L

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) = Unit
        override fun onDone(utteranceId: String) {
            pending -= utteranceId
        }
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String) {
            pending -= utteranceId
        }
        override fun onError(utteranceId: String, errorCode: Int) {
            pending -= utteranceId
        }
        override fun onStop(utteranceId: String, interrupted: Boolean) {
            pending -= utteranceId
        }
    }

    /** 음성 합성 엔진을 미리 연결해 둔다. 여러 번 불러도 된다. */
    fun prepare() {
        if (tts != null) return
        _state.value = State.Preparing
        tts = TextToSpeech(appContext) { status -> onInit(status) }
    }

    private fun onInit(status: Int) {
        val engine = tts
        if (status != TextToSpeech.SUCCESS || engine == null) {
            _state.value = State.Unavailable("음성 합성 엔진을 사용할 수 없습니다")
            return
        }
        val language = engine.setLanguage(Locale.KOREAN)
        if (language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED) {
            _state.value = State.Unavailable(
                "한국어 음성 데이터가 없습니다. 휴대전화 설정의 글자 읽어주기(TTS)에서 한국어 음성을 설치해 주세요"
            )
            return
        }
        // 오프라인에서도 들리도록 기기에 설치된 음성 가운데 가장 좋은 것을 고른다.
        runCatching { engine.voices }.getOrNull()
            ?.filter {
                it.locale.language == Locale.KOREAN.language &&
                    !it.isNetworkConnectionRequired &&
                    TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features
            }
            ?.maxByOrNull { it.quality }
            ?.let { engine.voice = it }
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        engine.setOnUtteranceProgressListener(progress)
        _state.value = State.Ready
    }

    /**
     * [text] 를 읽는다. 읽을 문장이 [TtsConfig.maxQueued] 개 이상 밀려 있으면 밀린 것을 버리고
     * 최신 문장부터 읽어, 음성이 실제 대화보다 계속 뒤처지지 않게 한다.
     */
    fun speak(text: String, cfg: TtsConfig): Boolean {
        val engine = tts ?: return false
        if (_state.value != State.Ready) return false
        engine.setSpeechRate(cfg.rate)
        engine.setPitch(cfg.pitch)
        val flush = pending.size >= cfg.maxQueued
        if (flush) pending.clear()
        val id = "u${nextId++}"
        pending += id
        val result = engine.speak(
            text,
            if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
            null,
            id,
        )
        if (result != TextToSpeech.SUCCESS) pending -= id
        return result == TextToSpeech.SUCCESS
    }

    /** 읽던 것과 밀린 것을 모두 멈춘다. */
    fun stop() {
        pending.clear()
        tts?.stop()
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
        pending.clear()
        _state.value = State.Off
    }
}
