package dev.localtrans.app.update

import android.content.Context
import dev.localtrans.app.net.Http
import dev.localtrans.app.net.HttpStatusException
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/** latest.json 확인 → APK 다운로드·검증 → 설치까지의 상태를 관리한다. */
class UpdateManager(
    private val context: Context,
    private val manifestUrl: String,
    private val installedVersionCode: Int,
) {
    sealed interface State {
        /** 배포 주소가 설정되지 않은 빌드 */
        data object Disabled : State
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val manifest: UpdateManifest) : State
        data class Downloading(val manifest: UpdateManifest, val progress: Float) : State
        data class Installing(val manifest: UpdateManifest) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(if (manifestUrl.isEmpty()) State.Disabled else State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val mutex = Mutex()

    /** "출처를 알 수 없는 앱 설치"가 이 앱에 허용되어 있는지 */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    suspend fun check() {
        if (manifestUrl.isEmpty() || !mutex.tryLock()) return
        try {
            _state.value = State.Checking
            val manifest = withContext(Dispatchers.IO) { UpdateManifest.parse(Http.getText(manifestUrl)) }
            _state.value =
                if (manifest.isNewerThan(installedVersionCode)) State.Available(manifest) else State.UpToDate
        } catch (e: CancellationException) {
            _state.value = State.Idle
            throw e
        } catch (e: HttpStatusException) {
            // 아직 릴리스를 한 번도 올리지 않은 저장소는 latest.json 이 404 다. 오류가 아니라 "새 버전 없음"이다.
            _state.value =
                if (e.code == 404) State.UpToDate else State.Failed("업데이트 확인 실패: ${e.message}")
        } catch (e: Exception) {
            _state.value = State.Failed("업데이트 확인 실패: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            mutex.unlock()
        }
    }

    suspend fun downloadAndInstall() {
        val manifest = (_state.value as? State.Available)?.manifest ?: return
        if (!mutex.tryLock()) return
        try {
            val dir = File(context.cacheDir, "update").apply {
                deleteRecursively()
                mkdirs()
            }
            val apk = File(dir, "app-${manifest.versionCode}.apk")
            _state.value = State.Downloading(manifest, 0f)
            ApkDownloader.download(manifest, apk) { p -> _state.value = State.Downloading(manifest, p) }
            _state.value = State.Installing(manifest)
            withContext(Dispatchers.IO) { ApkInstaller.install(context, apk) }
        } catch (e: CancellationException) {
            _state.value = State.Available(manifest)
            throw e
        } catch (e: Exception) {
            _state.value = State.Failed("업데이트 실패: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            mutex.unlock()
        }
    }

    fun onInstallFailed(message: String) {
        // Play Protect 가 처음 보는 APK 에 대해 띄우는 검사 창에서 "설치 안 함"을 고른 경우
        val reason =
            if ("VERIFICATION_FAILURE" in message) "Play Protect 검사 창에서 설치가 취소되었습니다" else message
        _state.value = State.Failed("설치 실패: $reason")
    }
}
