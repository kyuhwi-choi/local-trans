package dev.localtrans.app.pipeline

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.ToneGenerator
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import android.os.PowerManager
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import dev.localtrans.app.LocalTransApp
import dev.localtrans.app.MainActivity
import dev.localtrans.app.R
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 통역하는 동안 떠 있는 포그라운드 서비스. 화면을 끄거나 다른 앱으로 넘어가도 마이크 입력과 번역이 이어지게 한다.
 * 통역 자체는 InterpreterViewModel 이 돌리고, 이 서비스는 알림을 띄우고 폰이 잠들지 않게 하며,
 * 이어폰의 재생/정지 버튼을 받아 "내 차례"(듣기 멈춤)를 켜고 끈다.
 */
class ListeningService : Service() {

    private val scope = MainScope()
    private val link get() = (application as LocalTransApp).session
    private var wakeLock: PowerManager.WakeLock? = null
    private var foreground = false
    private var media: MediaSession? = null
    private var tones: ToneGenerator? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> link.commands.tryEmit(SessionLink.Command.Stop)
            ACTION_TOGGLE_PAUSE -> link.commands.tryEmit(SessionLink.Command.TogglePause)
            else -> enterForeground()
        }
        // 통역이 이미 끝난 뒤에 알림 버튼이 눌린 경우에는 서비스만 남지 않게 한다.
        if (!foreground) stopSelf()
        return START_NOT_STICKY
    }

    private fun enterForeground() {
        if (foreground) return
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "통역 중", NotificationManager.IMPORTANCE_LOW)
        )
        try {
            startForeground(
                NOTIFICATION_ID,
                notification(link.status.value),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } catch (e: Exception) {
            // 포그라운드로 올리지 못해도 화면이 켜져 있는 동안의 통역은 그대로 된다.
            DebugTrace.log("foreground service failed: $e")
            return
        }
        foreground = true
        // 화면이 꺼져도 인식과 번역이 멈추지 않게 한다. 통역이 끝나 서비스가 내려갈 때 푼다.
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "localtrans:listening")
            .apply { acquire() }
        listenForMediaButtons()
        scope.launch {
            link.status.collect { status ->
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(status))
                media?.setPlaybackState(playbackState(status))
            }
        }
    }

    /**
     * 이어폰의 재생/정지 버튼을 이 앱이 받게 한다. 듣는 중을 "재생 중", 듣기 멈춤을 "일시정지"로 알려 두면
     * 이어폰을 한 번 누를 때마다 정지/재생 신호가 번갈아 온다.
     */
    private fun listenForMediaButtons() {
        val session = MediaSession(this, "localtrans")
        session.setCallback(object : MediaSession.Callback() {
            override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                val key = IntentCompat.getParcelableExtra(mediaButtonIntent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                    ?: return false
                val pause = when (key.keyCode) {
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> null
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> true
                    KeyEvent.KEYCODE_MEDIA_PLAY -> false
                    else -> return false
                }
                if (key.action == KeyEvent.ACTION_DOWN && key.repeatCount == 0) onEarphoneButton(pause)
                return true
            }
        })
        session.setPlaybackState(playbackState(link.status.value))
        session.isActive = true
        media = session
        tones = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, TONE_VOLUME) }.getOrNull()
        claimMediaButtons()
    }

    /**
     * 미디어 버튼은 마지막으로 소리를 낸 앱에 전달된다. 번역을 읽는 음성은 음성 합성 앱이 내는 소리라
     * 이 앱이 낸 것으로 치지 않으므로, 아주 짧은 무음을 한 번 재생해 둔다.
     */
    private fun claimMediaButtons() {
        runCatching {
            val samples = ShortArray(SILENCE_SAMPLES)
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SILENCE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(samples.size * 2)
                .build()
            track.write(samples, 0, samples.size)
            track.play()
            scope.launch {
                delay(1_000)
                track.release()
            }
        }.onFailure { DebugTrace.log("silent track failed: $it") }
    }

    /** [pause] 가 true 면 듣기를 멈추고, false 면 다시 듣고, null 이면 지금과 반대로 바꾼다. */
    private fun onEarphoneButton(pause: Boolean?) {
        val paused = when (link.status.value) {
            SessionLink.Status.Listening -> false
            SessionLink.Status.Paused -> true
            else -> return
        }
        if (pause == paused) return
        DebugTrace.log("earphone button: ${if (paused) "resume" else "pause"}")
        link.commands.tryEmit(SessionLink.Command.TogglePause)
        // 화면을 보지 않고도 알 수 있게 이어폰으로 알린다: 멈추면 두 번, 다시 들으면 한 번 울린다.
        tones?.startTone(if (paused) ToneGenerator.TONE_PROP_BEEP else ToneGenerator.TONE_PROP_BEEP2)
    }

    private fun playbackState(status: SessionLink.Status): PlaybackState =
        PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE)
            .setState(
                if (status == SessionLink.Status.Listening) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                1f,
            )
            .build()

    private fun notification(status: SessionLink.Status): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_fg)
            .setContentTitle(
                when (status) {
                    SessionLink.Status.Starting -> "통역 준비 중"
                    SessionLink.Status.Paused -> "듣기 멈춤 (내 차례)"
                    else -> "통역 중"
                }
            )
            .setContentText(
                when (status) {
                    SessionLink.Status.Starting -> "모델을 올리고 있습니다"
                    SessionLink.Status.Paused -> "다시 듣기를 누르면 통역을 이어 갑니다"
                    else -> "들리는 영어를 통역하고 있습니다"
                }
            )
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (status == SessionLink.Status.Listening || status == SessionLink.Status.Paused) {
            val label = if (status == SessionLink.Status.Paused) "다시 듣기" else "내 차례"
            builder.addAction(0, label, action(ACTION_TOGGLE_PAUSE, 1))
        }
        builder.addAction(0, "중지", action(ACTION_STOP, 2))
        return builder.build()
    }

    private fun action(name: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, ListeningService::class.java).setAction(name),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    override fun onDestroy() {
        scope.cancel()
        media?.release()
        media = null
        tones?.release()
        tones = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "listening"
        private const val NOTIFICATION_ID = 2
        private const val ACTION_STOP = "dev.localtrans.app.action.STOP"
        private const val ACTION_TOGGLE_PAUSE = "dev.localtrans.app.action.TOGGLE_PAUSE"
        private const val TONE_VOLUME = 70
        private const val SILENCE_RATE = 16_000
        private const val SILENCE_SAMPLES = SILENCE_RATE / 5

        /**
         * 통역 시작 버튼을 누른 직후, 앱이 화면에 보이는 동안 불러야 한다.
         * 백그라운드에서는 마이크용 포그라운드 서비스를 시작할 수 없다.
         */
        fun start(context: Context) {
            runCatching { context.startService(Intent(context, ListeningService::class.java)) }
                .onFailure { DebugTrace.log("service start failed: $it") }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ListeningService::class.java))
        }
    }
}
