package dev.localtrans.app.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * APK를 다시 빌드하지 않고 바꿀 수 있는 동작 설정.
 * 저장소의 remote/config.json 을 고쳐 올리면 앱이 시작할 때 받아 온다.
 */
@Serializable
data class AppConfig(
    val stt: SttConfig = SttConfig(),
    val llm: LlmConfig = LlmConfig(),
    val prompts: PromptConfig = PromptConfig(),
    val tts: TtsConfig = TtsConfig(),
) {
    fun toJson(): String = lenientJson.encodeToString(serializer(), this)

    companion object {
        private val lenientJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
        }
        private val strictJson = Json { ignoreUnknownKeys = false }

        /** 앱에서 사용: 모르는 키는 무시해 구버전 앱이 새 설정 파일로 깨지지 않게 한다. */
        fun parse(text: String): AppConfig = lenientJson.decodeFromString(serializer(), text)

        /** 테스트에서 사용: 오타 난 키를 잡는다. */
        fun parseStrict(text: String): AppConfig = strictJson.decodeFromString(serializer(), text)
    }
}

@Serializable
data class SttConfig(
    val numThreads: Int = 2,
    /** 말한 뒤 이만큼 조용하면 한 구간으로 끊어 번역을 시작한다. 짧을수록 빠르지만 문장이 잘게 잘린다. */
    val endpointSilenceSec: Float = 0.6f,
    /** 아무 말도 인식되지 않은 채 이만큼 지나면 스트림을 초기화한다. */
    val idleSilenceSec: Float = 2.4f,
    /** 쉬지 않고 말해도 이 길이에서 강제로 끊는다. */
    val maxUtteranceSec: Float = 8f,
)

@Serializable
data class LlmConfig(
    val modelFile: String = "gemma-4-E2B-it.litertlm",
    /** gpu | npu | cpu. 실패하면 cpu로 내려간다. */
    val backend: String = "gpu",
    val translateMaxTokens: Int = 128,
    val suggestMaxTokens: Int = 192,
    val translateTemperature: Float = 0.2f,
    val suggestTemperature: Float = 0.7f,
    val topK: Int = 40,
    val topP: Float = 0.95f,
    /** 번역할 때 참고로 넘기는 직전 발화 수 */
    val contextUtterances: Int = 2,
    val suggestionCount: Int = 3,
)

/** 블루투스 모드에서 번역을 읽어 주는 음성 설정 */
@Serializable
data class TtsConfig(
    /** 말하는 속도. 1.0 이 보통이고, 대화를 따라가도록 조금 빠르게 둔다. */
    val rate: Float = 1.15f,
    val pitch: Float = 1.0f,
    /** 읽을 문장이 이만큼 밀리면 밀린 것을 버리고 최신 문장부터 읽는다. */
    val maxQueued: Int = 3,
)

@Serializable
data class PromptConfig(
    val translateSystem: String =
        "You are a real-time interpreter for a Korean listener. Translate the English speech " +
            "transcript into natural spoken Korean using polite 존댓말. The transcript comes from " +
            "speech recognition, so it may lack punctuation or contain small errors; infer the " +
            "intended meaning. Output only the Korean translation, with no explanations or notes.",
    /** {context} 는 직전 발화(없으면 빈 문자열), {text} 는 번역할 문장 */
    val translateUser: String = "{context}Translate only this sentence into Korean:\n{text}",
    /** {n} 은 제안 개수 */
    val suggestSystem: String =
        "You help a Korean speaker reply in an English conversation. Given what the other " +
            "person just said, suggest {n} short, natural English replies the user could say next. " +
            "Make them different in intent (for example: agree, ask a follow-up question, politely " +
            "decline). Output exactly {n} lines and nothing else: no introduction, no numbering, " +
            "no notes in parentheses. Each line must be: English reply | 한국어 뜻",
    /** {context} 는 직전 발화, {text} 는 상대가 방금 한 말 */
    val suggestUser: String = "{context}They said:\n{text}",
)
