package dev.localtrans.app

import android.app.Application
import dev.localtrans.app.config.ConfigRepository
import dev.localtrans.app.model.ModelStore
import dev.localtrans.app.pipeline.SessionLink
import dev.localtrans.app.update.UpdateManager
import java.io.File

class LocalTransApp : Application() {
    lateinit var config: ConfigRepository
        private set
    lateinit var updates: UpdateManager
        private set
    lateinit var models: ModelStore
        private set

    /** 통역 상태를 알림(ListeningService)과 주고받는다. */
    val session = SessionLink()

    override fun onCreate() {
        super.onCreate()
        // 디버그 빌드에서는 앱 폴더의 debug-config.json 으로 설정을 바꿔 시험할 수 있다 (bench/e2e).
        val override = getExternalFilesDir(null)?.let { File(it, "debug-config.json") }.takeIf { BuildConfig.DEBUG }
        config = ConfigRepository(File(filesDir, "config.json"), BuildConfig.REMOTE_CONFIG_URL, override)
        updates = UpdateManager(this, BuildConfig.UPDATE_MANIFEST_URL, BuildConfig.VERSION_CODE)
        models = ModelStore(this)
    }
}
