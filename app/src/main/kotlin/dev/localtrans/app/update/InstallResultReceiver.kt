package dev.localtrans.app.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat
import dev.localtrans.app.LocalTransApp

/** PackageInstaller 세션의 결과를 받는다. 성공하면 앱 프로세스가 종료되므로 여기까지 오지 않는 경우가 많다. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val updates = (context.applicationContext as LocalTransApp).updates
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // 시스템이 사용자 확인을 요구한 경우: 확인 화면을 띄운다.
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(confirm) }
                        .onFailure { updates.onInstallFailed("설치 확인 화면을 열 수 없습니다") }
                } else {
                    updates.onInstallFailed("설치 확인 화면 정보가 없습니다")
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                updates.onInstallFailed(message ?: "상태 코드 $status")
            }
        }
    }

    companion object {
        const val ACTION = "dev.localtrans.app.INSTALL_RESULT"
    }
}
