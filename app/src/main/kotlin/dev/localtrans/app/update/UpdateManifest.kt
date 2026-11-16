package dev.localtrans.app.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 릴리스마다 APK와 함께 올리는 latest.json 의 내용 */
@Serializable
data class UpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val sizeBytes: Long = 0,
    val notes: String = "",
) {
    fun isNewerThan(installedVersionCode: Int): Boolean = versionCode > installedVersionCode

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun parse(text: String): UpdateManifest = json.decodeFromString(serializer(), text)
    }
}
