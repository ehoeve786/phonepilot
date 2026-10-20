package app.pocketpilot.capability.api

/** Microphone mute, the speakerphone, and the media volume. */
interface AudioControl {
    fun setMicrophoneMuted(muted: Boolean)

    fun setSpeakerphone(on: Boolean)

    fun adjustMediaVolume(up: Boolean)
}
