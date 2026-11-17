package dev.localtrans.app.config

import dev.localtrans.app.net.Http
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * 내장 기본값 → 마지막으로 받은 캐시 → 원격 순으로 덮어쓴다. 오프라인이면 캐시로 동작한다.
 * [override] 파일이 있으면(디버그 빌드의 벤치마크용) 그 설정만 쓰고 원격 설정은 받지 않는다.
 */
class ConfigRepository(
    private val cacheFile: File,
    private val remoteUrl: String,
    private val override: File? = null,
) {

    enum class Source { Default, Cache, Remote, Override }

    private val _source = MutableStateFlow(Source.Default)
    val source: StateFlow<Source> = _source.asStateFlow()

    private val _config = MutableStateFlow(loadCached())
    val config: StateFlow<AppConfig> = _config.asStateFlow()

    private fun loadCached(): AppConfig {
        if (override?.isFile == true) {
            _source.value = Source.Override
            return AppConfig.parse(override.readText())
        }
        if (!cacheFile.exists()) return AppConfig()
        return runCatching { AppConfig.parse(cacheFile.readText()) }
            .onSuccess { _source.value = Source.Cache }
            .getOrDefault(AppConfig())
    }

    /** @return 원격 설정을 받아 반영했으면 true. 주소가 없거나 실패하면 false(기존 설정 유지). */
    suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
        if (remoteUrl.isEmpty() || _source.value == Source.Override) return@withContext false
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
