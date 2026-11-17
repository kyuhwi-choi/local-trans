package dev.localtrans.app.pipeline

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 통역을 돌리는 쪽(InterpreterViewModel)과 알림을 띄우는 쪽(ListeningService)을 잇는다.
 * 통역 상태는 알림에 보이고, 알림의 버튼은 명령으로 돌아온다.
 */
class SessionLink {
    enum class Status { Off, Starting, Listening, Paused }

    enum class Command { Stop, TogglePause }

    val status = MutableStateFlow(Status.Off)
    val commands = MutableSharedFlow<Command>(extraBufferCapacity = 4)

    companion object {
        fun statusOf(phase: Phase, paused: Boolean): Status = when (phase) {
            Phase.Idle -> Status.Off
            Phase.Starting -> Status.Starting
            Phase.Listening -> if (paused) Status.Paused else Status.Listening
        }
    }
}
