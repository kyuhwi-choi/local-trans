package dev.localtrans.app

import android.app.Application
import dev.localtrans.app.config.ConfigRepository
import dev.localtrans.app.model.ModelStore
import dev.localtrans.app.update.UpdateManager
import java.io.File

class LocalTransApp : Application() {
    lateinit var config: ConfigRepository
        private set
    lateinit var updates: UpdateManager
        private set
    lateinit var models: ModelStore
        private set

    override fun onCreate() {
        super.onCreate()
        config = ConfigRepository(File(filesDir, "config.json"), BuildConfig.REMOTE_CONFIG_URL)
        updates = UpdateManager(this, BuildConfig.UPDATE_MANIFEST_URL, BuildConfig.VERSION_CODE)
        models = ModelStore(this)
    }
}
