package dev.localtrans.app

import dev.localtrans.app.config.PromptConfig
import dev.localtrans.app.llm.Prompts
import dev.localtrans.app.llm.Suggestion
import dev.localtrans.app.llm.SuggestionParser
import dev.localtrans.app.stt.TextNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextTest {
    @Test
    fun uppercaseTranscriptBecomesSentenceCase() {
        assertEquals("Hello I'm fine and I think so", TextNormalizer.normalize("  HELLO I'M  FINE AND I THINK SO "))
        assertEquals("Hello I'm here", TextNormalizer.normalize("HELLO I'M HERE"))
        assertEquals("", TextNormalizer.normalize("   "))
    }

    @Test
    fun mixedCaseTranscriptIsKept() {
        assertEquals("Where is the iPhone?", TextNormalizer.normalize("Where is  the iPhone?"))
    }

    @Test
    fun parsesSuggestionLinesWithNoise() {
        val raw = """
            1. Sure, sounds good. | 네, 좋아요.
            - "Could you say that again?" | 다시 말씀해 주시겠어요?

            Sorry, I can't make it
            4) extra | 넘침
        """.trimIndent()
        assertEquals(
            listOf(
                Suggestion("Sure, sounds good.", "네, 좋아요."),
                Suggestion("Could you say that again?", "다시 말씀해 주시겠어요?"),
                Suggestion("Sorry, I can't make it", ""),
            ),
            SuggestionParser.parse(raw, 3),
        )
    }

    @Test
    fun fillsPromptTemplates() {
        val cfg = PromptConfig()
        val noContext = Prompts.translateUser(cfg, "How are you", emptyList())
        assertEquals("Translate to Korean:\nHow are you", noContext)
        val withContext = Prompts.translateUser(cfg, "How are you", listOf("Hi there"))
        assertTrue(withContext.startsWith("Earlier in the conversation:\nHi there\n\n"))
        assertTrue(Prompts.suggestSystem(cfg, 3).contains("suggest 3 short"))
    }

    @Test
    fun cleansTranslationDecorations() {
        assertEquals("안녕하세요", Prompts.cleanTranslation(" Korean: \"안녕하세요\" "))
    }
}
