package app.pocketpilot.core.model

import kotlinx.serialization.Serializable

/** Device capabilities that tools need; each has ordered backends (spec section 6). */
@Serializable
enum class CapabilityId {
    READ_UI,
    INJECT_INPUT,
    CAPTURE_SCREEN,
    STREAM_SCREEN,
    LAUNCH_APPS,
    READ_NOTIFICATIONS,
    WRITE_SETTINGS,
    RUN_SHELL,
    RUN_TERMUX,
    READ_FILES,
}
