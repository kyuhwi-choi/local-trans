package dev.localtrans.app.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 이어폰(블루투스 우선)이 연결되어 있는지 지켜본다. 연결되어 있으면 이름을, 없으면 null 을 알려 준다. */
class EarphoneMonitor(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val _name = MutableStateFlow<String?>(null)
    val name = _name.asStateFlow()

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refresh()
    }

    fun start() {
        audio.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        refresh()
    }

    fun stop() {
        audio.unregisterAudioDeviceCallback(callback)
    }

    /** 이어폰 마이크가 아니라 폰에 달린 마이크. 블루투스 모드에서 녹음 입력으로 고정하는 데 쓴다. */
    fun builtInMic(): AudioDeviceInfo? =
        audio.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }

    private fun refresh() {
        val outputs = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        _name.value = pick(outputs.map { it.type to it.productName?.toString().orEmpty() })
    }

    companion object {
        private val bluetooth = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_HEARING_AID,
        )
        private val wired = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
        )

        /** (기기 종류, 이름) 목록에서 화면에 보여 줄 이어폰 이름을 고른다. 블루투스가 유선보다 먼저다. */
        internal fun pick(outputs: List<Pair<Int, String>>): String? {
            outputs.firstOrNull { it.first in bluetooth }?.let { return it.second.ifBlank { "블루투스 이어폰" } }
            // 유선 이어폰의 이름은 폰 모델명으로 나오므로 쓰지 않는다.
            return if (outputs.any { it.first in wired }) "유선 이어폰" else null
        }
    }
}
