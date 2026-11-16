package dev.localtrans.app.ui

import dev.localtrans.app.pipeline.Mode
import dev.localtrans.app.pipeline.ModelState
import dev.localtrans.app.pipeline.Phase
import dev.localtrans.app.tts.KoreanSpeaker

/** 화면에 보여 줄 상태 문구. 화면 코드와 분리해 테스트할 수 있게 했다. */
internal object StatusText {
    data class Line(val text: String, val warning: Boolean = false)

    /** 블루투스 모드에서 모드 선택 아래에 보여 주는 한 줄 */
    fun bluetooth(earphone: String?, voice: KoreanSpeaker.State): Line = when {
        voice is KoreanSpeaker.State.Unavailable -> Line(voice.reason, warning = true)
        earphone == null ->
            Line("이어폰이 연결되지 않았습니다. 연결하면 번역을 한국어 음성으로 들려줍니다.", warning = true)
        voice == KoreanSpeaker.State.Ready -> Line("$earphone(으)로 번역을 들려줍니다 · 영어는 폰 마이크로 듣습니다")
        else -> Line("$earphone · 한국어 음성 준비 중")
    }

    /** 자막이 아직 없을 때 화면 가운데에 보여 주는 안내 */
    fun empty(phase: Phase, models: ModelState, mode: Mode): String = when {
        phase == Phase.Listening -> "듣고 있습니다…"
        models == ModelState.Loading -> "모델을 올리는 중입니다…"
        models == ModelState.Ready -> "모델이 준비되었습니다. 시작을 누르면 바로 통역합니다"
        mode == Mode.Bluetooth -> "시작을 누르면 들리는 영어를 자막과 이어폰 음성으로 통역합니다.\n모델을 미리 올려 두면 바로 시작됩니다"
        else -> "시작을 누르면 들리는 영어를 한국어 자막으로 보여 줍니다.\n모델을 미리 올려 두면 바로 시작됩니다"
    }
}
