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
    RING_VOLUME("ring_volume", "0 to 100 (percent)"),
    ALARM_VOLUME("alarm_volume", "0 to 100 (percent)"),
    NOTIFICATION_VOLUME("notification_volume", "0 to 100 (percent)"),
    AIRPLANE_MODE("airplane_mode", "on or off (on cuts remote connections)"),
    MOBILE_DATA("mobile_data", "on or off"),
    LOCATION("location", "on or off"),
    BATTERY_SAVER("battery_saver", "on or off"),
    STAY_AWAKE("stay_awake", "on or off (screen stays on while charging)"),
    SCREEN_TIMEOUT("screen_timeout", "15 to 1800 (seconds)"),
    FONT_SCALE("font_scale", "0.8 to 2.0 (1.0 is the default size)"),
    ANIMATIONS("animations", "on or off (system animations)"),
    ;

    /** [value] in canonical form, or null when this key does not accept it. */
    fun normalize(value: String): String? {
        val v = value.trim().lowercase()
        return when (this) {
            DARK_MODE -> {
                v.takeIf { it in setOf(ON, OFF, AUTO) }
            }

            WIFI, BLUETOOTH, DND, AIRPLANE_MODE, MOBILE_DATA, LOCATION, BATTERY_SAVER, STAY_AWAKE, ANIMATIONS -> {
                v.takeIf { it in setOf(ON, OFF) }
            }

            ROTATION -> {
                v.takeIf { it in setOf(AUTO, LOCKED) }
            }

            BRIGHTNESS -> {
                if (v == AUTO) v else percent(v)
            }

            VOLUME, RING_VOLUME, ALARM_VOLUME, NOTIFICATION_VOLUME -> {
                percent(v)
            }

            SCREEN_TIMEOUT -> {
                v
                    .removeSuffix("s")
                    .toIntOrNull()
                    ?.takeIf { it in 15..1800 }
                    ?.toString()
            }

            FONT_SCALE -> {
                v.toDoubleOrNull()?.takeIf { it in 0.8..2.0 }?.let { "%.2f".format(java.util.Locale.ROOT, it).trimEnd('0').trimEnd('.') }
            }
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
