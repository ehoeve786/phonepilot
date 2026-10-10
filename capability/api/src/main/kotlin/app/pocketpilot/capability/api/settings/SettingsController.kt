package app.pocketpilot.capability.api.settings

import app.pocketpilot.capability.api.CapabilityBackend

/**
 * The device settings a model may read and change (spec section 5, `settings.get` / `settings.set`):
 * a fixed allowlist, each with its own values, so no caller can reach arbitrary settings.
 */
enum class SettingKey(
    val wire: String,
    /** What `set` accepts, for the tool description and errors. */
    val values: String,
) {
    DARK_MODE("dark_mode", "on, off or auto"),
    WIFI("wifi", "on or off"),
    BLUETOOTH("bluetooth", "on or off"),
    DND("dnd", "on or off (Do Not Disturb)"),
    BRIGHTNESS("brightness", "0 to 100 (percent), or auto"),
    ROTATION("rotation", "auto or locked"),
    VOLUME("volume", "0 to 100 (percent of media volume)"),
    ;

    /** [value] in canonical form, or null when this key does not accept it. */
    fun normalize(value: String): String? {
        val v = value.trim().lowercase()
        return when (this) {
            DARK_MODE -> v.takeIf { it in setOf(ON, OFF, AUTO) }
            WIFI, BLUETOOTH, DND -> v.takeIf { it in setOf(ON, OFF) }
            ROTATION -> v.takeIf { it in setOf(AUTO, LOCKED) }
            BRIGHTNESS -> if (v == AUTO) v else percent(v)
            VOLUME -> percent(v)
        }
    }

    companion object {
        const val ON = "on"
        const val OFF = "off"
        const val AUTO = "auto"
        const val LOCKED = "locked"

        fun fromWire(wire: String): SettingKey? = entries.firstOrNull { it.wire == wire.trim().lowercase() }

        private fun percent(v: String): String? =
            v
                .removeSuffix("%")
                .toIntOrNull()
                ?.takeIf { it in 0..100 }
                ?.toString()
    }
}

/** Reads and changes [SettingKey]s; values are in the canonical forms [SettingKey.normalize] returns. */
interface SettingsController : CapabilityBackend {
    suspend fun get(key: SettingKey): String

    suspend fun set(
        key: SettingKey,
        value: String,
    )
}
