package dev.localtrans.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.localtrans.app.BuildConfig
import dev.localtrans.app.llm.Suggestion
import dev.localtrans.app.pipeline.InterpreterViewModel
import dev.localtrans.app.pipeline.Mode
import dev.localtrans.app.pipeline.ModelState
import dev.localtrans.app.pipeline.Phase
import dev.localtrans.app.pipeline.UiState
import dev.localtrans.app.pipeline.Utterance
import dev.localtrans.app.update.UpdateManager

@Composable
fun LocalTransTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colors = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
fun MainScreen(vm: InterpreterViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val update by vm.updates.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val view = LocalView.current

    // 자막을 보는 앱이므로 듣는 동안에는 화면이 꺼지지 않게 한다.
    val listening = ui.phase == Phase.Listening
    DisposableEffect(listening) {
        view.keepScreenOn = listening
        onDispose { view.keepScreenOn = false }
    }

    val permissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true) vm.start()
        else vm.showError("마이크 권한이 있어야 통역할 수 있습니다")
    }
    val onStart = {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) vm.start()
        else permissions.launch(
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        )
    }
    val onInstall = {
        if (vm.updates.canInstall()) {
            vm.installUpdate()
        } else {
            // 최초 1회: 이 앱에 "출처를 알 수 없는 앱 설치"를 허용해야 한다.
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}"),
                )
            )
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp),
        ) {
            TopBar(ui = ui, update = update, onCheckUpdate = vm::checkForUpdate, onRecording = vm::setRecording)
            UpdateBanner(update = update, onInstall = onInstall, onRetry = vm::checkForUpdate)
            ModeBar(ui = ui, onMode = vm::setMode)
            if (ui.missingModels.isNotEmpty()) {
                MissingModelsCard(ui = ui, onRecheck = vm::refreshModelStatus)
            }
            ui.error?.let { ErrorCard(message = it, onDismiss = { vm.showError(null) }) }

            Transcript(ui = ui, modifier = Modifier.weight(1f))

            Suggestions(suggestions = ui.suggestions, loading = ui.suggesting)
            Spacer(Modifier.height(8.dp))
            if (listening) {
                TurnButton(paused = ui.paused, onToggle = { vm.setPaused(!ui.paused) })
                Spacer(Modifier.height(8.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModelButton(
                    ui = ui,
                    onLoad = vm::loadModels,
                    onUnload = vm::unloadModels,
                    modifier = Modifier.weight(1f),
                )
                ControlButton(
                    phase = ui.phase,
                    enabled = ui.missingModels.isEmpty(),
                    onStart = onStart,
                    onStop = vm::stop,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun TopBar(
    ui: UiState,
    update: UpdateManager.State,
    onCheckUpdate: () -> Unit,
    onRecording: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("로컬 통역", style = MaterialTheme.typography.titleLarge)
            val backend = if (ui.backend.isNotEmpty()) " · ${ui.backend.uppercase()}" else ""
            val secondPass = if (ui.secondPass) " · 2단계 인식" else ""
            val status = when (update) {
                UpdateManager.State.Checking -> " · 업데이트 확인 중"
                UpdateManager.State.UpToDate -> " · 최신 버전"
                else -> ""
            }
            Text(
                "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})$backend$secondPass$status",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // 디버그 빌드 전용: 통역할 때 들은 음성과 결과를 파일로 남긴다 (bench/e2e 에서 다시 돌려 볼 수 있다).
        if (BuildConfig.DEBUG) {
            FilterChip(
                selected = ui.recording,
                onClick = { onRecording(!ui.recording) },
                enabled = ui.phase == Phase.Idle,
                label = { Text("음성 저장") },
            )
        }
        if (update != UpdateManager.State.Disabled) {
            TextButton(onClick = onCheckUpdate, enabled = update != UpdateManager.State.Checking) {
                Text("업데이트 확인")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeBar(ui: UiState, onMode: (Mode) -> Unit) {
    val modes = Mode.entries
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
    ) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            modes.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = ui.mode == mode,
                    onClick = { onMode(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                ) {
                    Text(
                        when (mode) {
                            Mode.Basic -> "기본 모드"
                            Mode.Bluetooth -> "블루투스 모드"
                        }
                    )
                }
            }
        }
        if (ui.mode == Mode.Bluetooth) {
            val line = StatusText.bluetooth(ui.earphone, ui.voice)
            Text(
                line.text,
                modifier = Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = if (line.warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun UpdateBanner(update: UpdateManager.State, onInstall: () -> Unit, onRetry: () -> Unit) {
    when (update) {
        is UpdateManager.State.Available -> Banner {
            val size = if (update.manifest.sizeBytes > 0) " · ${update.manifest.sizeBytes / 1_000_000}MB" else ""
            Text("새 버전 ${update.manifest.versionName}$size", fontWeight = FontWeight.SemiBold)
            if (update.manifest.notes.isNotBlank()) {
                Text(update.manifest.notes, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onInstall) { Text("업데이트") }
        }
        is UpdateManager.State.Downloading -> Banner {
            Text("버전 ${update.manifest.versionName} 받는 중 · ${(update.progress * 100).toInt()}%")
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(progress = { update.progress }, modifier = Modifier.fillMaxWidth())
        }
        is UpdateManager.State.Installing -> Banner {
            Text("설치 중입니다. 끝나면 앱이 닫히고 알림에서 다시 열 수 있습니다.")
            Text(
                "Play Protect 검사 창이 뜨면 검사를 선택해야 설치가 이어집니다.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        is UpdateManager.State.Failed -> Banner {
            Text(update.message, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onRetry) { Text("다시 확인") }
        }
        else -> Unit
    }
}

@Composable
private fun Banner(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(modifier = Modifier.padding(12.dp)) { content() }
    }
}

@Composable
private fun MissingModelsCard(ui: UiState, onRecheck: () -> Unit) {
    Banner {
        Text("모델 파일이 없습니다", fontWeight = FontWeight.SemiBold)
        Text(
            "PC에서 scripts/push-models.sh 를 실행해 아래 폴더에 넣어 주세요.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(4.dp))
        Text(ui.modelDir, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic)
        ui.missingModels.forEach { Text("· $it", style = MaterialTheme.typography.bodySmall) }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onRecheck) { Text("다시 확인") }
    }
}

@Composable
private fun ErrorCard(message: String, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(modifier = Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
            TextButton(onClick = onDismiss) { Text("닫기") }
        }
    }
}

/** 목록 끝까지 내리기 위한 넉넉한 오프셋. 실제 스크롤은 내용 끝에서 멈춘다. */
private const val SCROLL_TO_END = 1_000_000

@Composable
private fun Transcript(ui: UiState, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    val lastKorean = ui.utterances.lastOrNull()?.korean.orEmpty()
    // 답변 제안이 나타나면 자막 영역이 아래에서부터 줄어들어 마지막 자막이 가려지므로, 영역 높이도 함께 지켜본다.
    val viewportHeight by remember { derivedStateOf { listState.layoutInfo.viewportSize.height } }
    // 새 자막이 들어오면 맨 아래로 따라간다. 마지막 자막이 화면보다 길어도 끝이 보이도록 끝까지 내린다.
    LaunchedEffect(ui.utterances.size, ui.partial, lastKorean, viewportHeight) {
        val count = ui.utterances.size + if (ui.partial.isNotEmpty()) 1 else 0
        if (count > 0) listState.scrollToItem(count - 1, scrollOffset = SCROLL_TO_END)
    }

    if (ui.utterances.isEmpty() && ui.partial.isEmpty()) {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                StatusText.empty(ui.phase, ui.models, ui.mode, ui.paused),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        return
    }

    LazyColumn(modifier = modifier.fillMaxWidth(), state = listState) {
        items(ui.utterances, key = { it.id }) { UtteranceRow(it) }
        if (ui.partial.isNotEmpty()) {
            item(key = "partial") {
                Text(
                    ui.partial,
                    modifier = Modifier.padding(vertical = 6.dp),
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun UtteranceRow(utterance: Utterance) {
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Text(
            utterance.english,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            // 번역이 아직 한 글자도 안 나왔을 때 줄 높이를 유지한다.
            if (utterance.korean.isEmpty() && !utterance.done) "…" else utterance.korean,
            fontSize = 22.sp,
            lineHeight = 30.sp,
            color = if (utterance.done) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
        )
        utterance.latencyMs?.let {
            Text(
                "번역 %.1f초".format(it / 1000f),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun Suggestions(suggestions: List<Suggestion>, loading: Boolean) {
    if (suggestions.isEmpty() && !loading) return
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "이렇게 답할 수 있어요",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (loading) {
                Spacer(Modifier.width(8.dp))
                CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
            }
        }
        suggestions.forEach { suggestion ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(suggestion.english, fontWeight = FontWeight.SemiBold)
                    if (suggestion.korean.isNotEmpty()) {
                        Text(
                            suggestion.korean,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** 통역과 별개로 모델을 미리 올리거나 메모리에서 내린다. 통역 중에는 내릴 수 없다. */
@Composable
private fun ModelButton(ui: UiState, onLoad: () -> Unit, onUnload: () -> Unit, modifier: Modifier = Modifier) {
    val idle = ui.phase == Phase.Idle
    when (ui.models) {
        ModelState.Unloaded -> OutlinedButton(
            onClick = onLoad,
            enabled = idle && ui.missingModels.isEmpty(),
            modifier = modifier,
        ) { Text("모델 올리기") }
        ModelState.Loading -> OutlinedButton(onClick = {}, enabled = false, modifier = modifier) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("올리는 중")
        }
        ModelState.Ready -> OutlinedButton(onClick = onUnload, enabled = idle, modifier = modifier) {
            Text("모델 내리기")
        }
    }
}

/** 내가 말할 차례에 듣기를 멈췄다가 다시 듣는다. 멈춘 동안에는 눈에 띄는 색으로 바꿔 다시 누르는 것을 잊지 않게 한다. */
@Composable
private fun TurnButton(paused: Boolean, onToggle: () -> Unit) {
    if (paused) {
        Button(
            onClick = onToggle,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
        ) { Text("듣기 멈춤 · 눌러서 다시 듣기") }
    } else {
        FilledTonalButton(onClick = onToggle, modifier = Modifier.fillMaxWidth()) {
            Text("내 차례 (듣기 멈춤)")
        }
    }
}

@Composable
private fun ControlButton(
    phase: Phase,
    enabled: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (phase) {
        Phase.Idle -> Button(onClick = onStart, enabled = enabled, modifier = modifier) {
            Text("통역 시작")
        }
        Phase.Starting -> OutlinedButton(onClick = onStop, modifier = modifier) {
            Text("시작 취소")
        }
        Phase.Listening -> Button(onClick = onStop, modifier = modifier) {
            Text("중지")
        }
    }
}
