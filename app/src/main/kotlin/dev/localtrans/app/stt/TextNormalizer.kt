package dev.localtrans.app.stt

object TextNormalizer {
    private val spaces = Regex("\\s+")
    private val loneI = Regex("\\bi(?=\\b|')")

    /**
     * 스트리밍 인식 모델 중에는 전부 대문자나 전부 소문자로 내보내는 것이 있다.
     * 화면 표시와 번역 품질을 위해 일반 문장 형태로 바꾼다. 이미 대소문자가 섞여 있으면 그대로 둔다.
     */
    fun normalize(raw: String): String {
        val text = raw.trim().replace(spaces, " ")
        if (text.isEmpty()) return ""
        val letters = text.filter { it.isLetter() }
        val mixed = letters.any { it.isLowerCase() } && letters.any { it.isUpperCase() }
        if (mixed) return text
        val lower = text.lowercase().replace(loneI, "I")
        return lower.replaceFirstChar { it.uppercase() }
    }
}
