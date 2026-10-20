package app.pocketpilot.capability.device

import android.content.Context
import android.media.AudioManager
import app.pocketpilot.capability.api.AudioControl

class AndroidAudioControl(
    context: Context,
) : AudioControl {
    private val audio = context.getSystemService(AudioManager::class.java)

    override fun setMicrophoneMuted(muted: Boolean) {
        audio.isMicrophoneMute = muted
    }

    override fun setSpeakerphone(on: Boolean) {
        audio.isSpeakerphoneOn = on
    }

    override fun adjustMediaVolume(up: Boolean) {
        val direction = if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
    }
}
