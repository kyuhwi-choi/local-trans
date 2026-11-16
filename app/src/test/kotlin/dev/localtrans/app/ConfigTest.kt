package dev.localtrans.app

import dev.localtrans.app.config.AppConfig
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigTest {
    @Test
    fun defaultsRoundTrip() {
        assertEquals(AppConfig(), AppConfig.parse(AppConfig().toJson()))
    }

    @Test
    fun partialConfigKeepsDefaultsAndIgnoresUnknownKeys() {
        val cfg = AppConfig.parse("""{"stt": {"endpointSilenceSec": 0.4}, "somethingNew": 1}""")
        assertEquals(0.4f, cfg.stt.endpointSilenceSec)
        assertEquals(AppConfig().llm, cfg.llm)
    }

    /** 저장소의 remote/config.json 에 오타 난 키가 없는지, 필수 자리표시자가 남아 있는지 확인한다. */
    @Test
    fun remoteConfigFileIsValid() {
        val file = File("../remote/config.json")
        assertTrue("remote/config.json 이 없습니다", file.isFile)
        val cfg = AppConfig.parseStrict(file.readText())
        assertTrue(cfg.prompts.translateUser.contains("{text}"))
        assertTrue(cfg.prompts.suggestUser.contains("{text}"))
        assertTrue(cfg.prompts.suggestSystem.contains("{n}"))
    }
}
