package dev.localtrans.app.config

import dev.localtrans.app.net.Http
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** 내장 기본값 → 마지막으로 받은 캐시 → 원격 순으로 덮어쓴다. 오프라인이면 캐시로 동작한다. */
class ConfigRepository(private val cacheFile: File, private val remoteUrl: String) {

    enum class Source { Default, Cache, Remote }

    private val _source = MutableStateFlow(Source.Default)
    val source: StateFlow<Source> = _source.asStateFlow()

    private val _config = MutableStateFlow(loadCached())
    val config: StateFlow<AppConfig> = _config.asStateFlow()

    private fun loadCached(): AppConfig {
        if (!cacheFile.exists()) return AppConfig()
        return runCatching { AppConfig.parse(cacheFile.readText()) }
            .onSuccess { _source.value = Source.Cache }
            .getOrDefault(AppConfig())
    }

    /** @return 원격 설정을 받아 반영했으면 true. 주소가 없거나 실패하면 false(기존 설정 유지). */
    suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
        if (remoteUrl.isEmpty()) return@withContext false
        runCatching {
            val text = Http.getText(remoteUrl)
            val parsed = AppConfig.parse(text)
            val tmp = File(cacheFile.parentFile, cacheFile.name + ".tmp")
            tmp.writeText(text)
            check(tmp.renameTo(cacheFile)) { "설정 캐시 저장 실패" }
            _config.value = parsed
            _source.value = Source.Remote
            true
        }.getOrDefault(false)
    }
}
