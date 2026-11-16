package dev.localtrans.app.llm

import dev.localtrans.app.config.PromptConfig

data class Suggestion(val english: String, val korean: String)

object Prompts {
    fun fill(template: String, vararg values: Pair<String, String>): String =
        values.fold(template) { acc, (key, value) -> acc.replace("{$key}", value) }

    private fun contextBlock(header: String, previous: List<String>): String =
        if (previous.isEmpty()) "" else header + "\n" + previous.joinToString("\n") { "- $it" } + "\n\n"

    // 소형 모델은 맥락으로 준 문장까지 번역해 버리기 쉬워서, 번역 대상이 아님을 분명히 적는다.
    fun translateUser(cfg: PromptConfig, text: String, previous: List<String>): String =
        fill(
            cfg.translateUser,
            "context" to contextBlock("Previous sentences, for context only (do not translate these):", previous),
            "text" to text,
        )

    fun suggestSystem(cfg: PromptConfig, count: Int): String = fill(cfg.suggestSystem, "n" to count.toString())

    fun suggestUser(cfg: PromptConfig, text: String, previous: List<String>): String =
        fill(cfg.suggestUser, "context" to contextBlock("Earlier in the conversation:", previous), "text" to text)

    /** 모델이 붙이기 쉬운 군더더기(따옴표, "Korean:" 머리말)를 떼어 낸다. */
    fun cleanTranslation(raw: String): String =
        raw.trim()
            .removePrefix("Korean:").removePrefix("한국어:")
            .trim()
            .trim('"', '“', '”')
            .trim()
}

object SuggestionParser {
    private val bullet = Regex("^\\s*(?:[-*•]|\\d+[.)])\\s*")
    // 한국어 뜻 뒤에 붙는 "(Agreeing)" 같은 영어 주석
    private val trailingNote = Regex("\\s*\\([A-Za-z][A-Za-z /,&-]*\\)\\s*$")

    /**
     * "English reply | 한국어 뜻" 형식의 줄들을 해석한다. 생성 도중의 미완성 출력에도 쓸 수 있도록
     * 번호·불릿이 붙거나 한국어 뜻이 아직 없는 줄도 받아들인다. "Here are 3 replies:" 같은 머리말 줄은 버린다.
     */
    fun parse(raw: String, max: Int): List<Suggestion> =
        raw.lineSequence()
            .map { it.replace(bullet, "").trim() }
            .filter { it.isNotEmpty() && !(it.endsWith(":") && '|' !in it) }
            .mapNotNull { line ->
                val cut = line.indexOf('|')
                val english = (if (cut >= 0) line.substring(0, cut) else line).trim().trim('"', '“', '”').trim()
                val korean = (if (cut >= 0) line.substring(cut + 1) else "").replace(trailingNote, "").trim()
                if (english.isEmpty()) null else Suggestion(english, korean)
            }
            .take(max)
            .toList()
}
