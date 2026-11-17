package dev.localtrans.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import dev.localtrans.app.pipeline.InterpreterViewModel
import dev.localtrans.app.ui.LocalTransTheme
import dev.localtrans.app.ui.MainScreen

class MainActivity : ComponentActivity() {
    private val vm: InterpreterViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) autostart(intent)
        enableEdgeToEdge()
        setContent {
            LocalTransTheme {
                MainScreen()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        autostart(intent)
    }

    /**
     * 디버그 빌드에서 `am start ... --es autostart <기록 이름>` 으로 띄우면 버튼을 누르지 않아도 통역을 시작한다.
     * bench/e2e/run.sh 가 음성 파일을 넣고 이렇게 띄운다.
     */
    private fun autostart(intent: Intent?) {
        if (!BuildConfig.DEBUG) return
        val name = intent?.getStringExtra("autostart") ?: return
        vm.start(sessionName = name)
    }
}
