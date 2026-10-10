package app.pocketpilot.capability.shizuku

import app.pocketpilot.capability.api.settings.SettingKey
import app.pocketpilot.capability.api.settings.SettingKey.Companion.AUTO
import app.pocketpilot.capability.api.settings.SettingKey.Companion.LOCKED
import app.pocketpilot.capability.api.settings.SettingKey.Companion.OFF
import app.pocketpilot.capability.api.settings.SettingKey.Companion.ON
import kotlin.math.roundToInt

/**
 * The fixed shell commands behind each [SettingKey], run as the shell user by [PrivilegedService].
 * [run] executes one argv array and returns its output, throwing when the command fails.
 */
internal class SettingCommands(
    private val run: (List<String>) -> String,
) {
    fun get(key: SettingKey): String =
        when (key) {
            SettingKey.DARK_MODE -> {
                when (run(listOf("cmd", "uimode", "night")).substringAfter(':').trim().lowercase()) {
                    "yes" -> ON
                    "no" -> OFF
                    "auto" -> AUTO
                    else -> "custom"
                }
            }

            // wifi_on is 1 or 2 (on, also in airplane mode) when Wi-Fi is enabled.
            SettingKey.WIFI -> {
                if (setting("global", "wifi_on") in setOf("1", "2")) ON else OFF
            }

            SettingKey.BLUETOOTH -> {
                if (setting("global", "bluetooth_on") == "1") ON else OFF
            }

            // zen_mode: 0 off; 1 priority only, 2 total silence, 3 alarms only.
            SettingKey.DND -> {
                if (setting("global", "zen_mode").let { it.isNotEmpty() && it != "0" && it != "null" }) ON else OFF
            }

            SettingKey.BRIGHTNESS -> {
                if (setting("system", "screen_brightness_mode") == "1") {
                    AUTO
                } else {
                    val raw = setting("system", "screen_brightness").toIntOrNull() ?: 0
                    (raw * 100.0 / MAX_BRIGHTNESS).roundToInt().coerceIn(0, 100).toString()
                }
            }

            SettingKey.ROTATION -> {
                if (setting("system", "accelerometer_rotation") == "1") AUTO else LOCKED
            }

            SettingKey.VOLUME, SettingKey.RING_VOLUME, SettingKey.ALARM_VOLUME, SettingKey.NOTIFICATION_VOLUME -> {
                val (level, max) = volume(stream(key))
                if (max == 0) "0" else (level * 100.0 / max).roundToInt().toString()
            }

            SettingKey.AIRPLANE_MODE -> {
                onOff(setting("global", "airplane_mode_on") == "1")
            }

            SettingKey.MOBILE_DATA -> {
                onOff(setting("global", "mobile_data") == "1")
            }

            SettingKey.LOCATION -> {
                onOff(setting("secure", "location_mode").let { it.isNotEmpty() && it != "0" && it != "null" })
            }

            SettingKey.BATTERY_SAVER -> {
                onOff(setting("global", "low_power") == "1")
            }

            SettingKey.STAY_AWAKE -> {
                onOff(setting("global", "stay_on_while_plugged_in").let { it.isNotEmpty() && it != "0" && it != "null" })
            }

            SettingKey.SCREEN_TIMEOUT -> {
                ((setting("system", "screen_off_timeout").toLongOrNull() ?: 0L) / 1000).toString()
            }

            SettingKey.FONT_SCALE -> {
                setting("system", "font_scale").takeUnless { it.isEmpty() || it == "null" } ?: "1"
            }

            SettingKey.ANIMATIONS -> {
                onOff(setting("global", "animator_duration_scale").toDoubleOrNull() != 0.0)
            }
        }

    /** [value] must already be normalized for [key]. */
    fun set(
        key: SettingKey,
        value: String,
    ) {
        require(key.normalize(value) == value) { "${key.wire} takes ${key.values}" }
        when (key) {
            SettingKey.DARK_MODE -> {
                run(listOf("cmd", "uimode", "night", mapOf(ON to "yes", OFF to "no", AUTO to "auto").getValue(value)))
            }

            SettingKey.WIFI -> {
                firstWorking(
                    listOf("cmd", "wifi", "set-wifi-enabled", if (value == ON) "enabled" else "disabled"),
                    listOf("svc", "wifi", if (value == ON) "enable" else "disable"),
                )
            }

            SettingKey.BLUETOOTH -> {
                firstWorking(
                    listOf("cmd", "bluetooth_manager", if (value == ON) "enable" else "disable"),
                    listOf("svc", "bluetooth", if (value == ON) "enable" else "disable"),
                )
            }

            SettingKey.DND -> {
                run(listOf("cmd", "notification", "set_dnd", value))
            }

            SettingKey.BRIGHTNESS -> {
                if (value == AUTO) {
                    put("system", "screen_brightness_mode", "1")
                } else {
                    put("system", "screen_brightness_mode", "0")
                    val raw = (value.toInt() * MAX_BRIGHTNESS / 100.0).roundToInt().coerceIn(1, MAX_BRIGHTNESS)
                    put("system", "screen_brightness", raw.toString())
                }
            }

            SettingKey.ROTATION -> {
                put("system", "accelerometer_rotation", if (value == AUTO) "1" else "0")
            }

            SettingKey.VOLUME, SettingKey.RING_VOLUME, SettingKey.ALARM_VOLUME, SettingKey.NOTIFICATION_VOLUME -> {
                val stream = stream(key)
                val (_, max) = volume(stream)
                val level = (value.toInt() * max / 100.0).roundToInt().coerceIn(0, max)
                run(listOf("cmd", "media_session", "volume", "--stream", stream, "--set", level.toString()))
            }

            SettingKey.AIRPLANE_MODE -> {
                run(listOf("cmd", "connectivity", "airplane-mode", if (value == ON) "enable" else "disable"))
            }

            SettingKey.MOBILE_DATA -> {
                run(listOf("svc", "data", if (value == ON) "enable" else "disable"))
            }

            SettingKey.LOCATION -> {
                firstWorking(
                    listOf("cmd", "location", "set-location-enabled", (value == ON).toString()),
                    listOf("settings", "put", "secure", "location_mode", if (value == ON) "3" else "0"),
                )
            }

            SettingKey.BATTERY_SAVER -> {
                firstWorking(
                    listOf("cmd", "power", "set-mode", if (value == ON) "1" else "0"),
                    listOf("settings", "put", "global", "low_power", if (value == ON) "1" else "0"),
                )
            }

            SettingKey.STAY_AWAKE -> {
                run(listOf("svc", "power", "stayon", (value == ON).toString()))
            }

            SettingKey.SCREEN_TIMEOUT -> {
                put("system", "screen_off_timeout", (value.toLong() * 1000).toString())
            }

            SettingKey.FONT_SCALE -> {
                put("system", "font_scale", value)
            }

            SettingKey.ANIMATIONS -> {
                val scale = if (value == ON) "1" else "0"
                for (name in listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale")) {
                    put("global", name, scale)
                }
            }
        }
    }

    private fun setting(
        namespace: String,
        name: String,
    ): String = run(listOf("settings", "get", namespace, name)).trim()

    private fun put(
        namespace: String,
        name: String,
        value: String,
    ) {
        run(listOf("settings", "put", namespace, name, value))
    }

    private fun onOff(on: Boolean) = if (on) ON else OFF

    private fun stream(key: SettingKey): String =
        when (key) {
            SettingKey.RING_VOLUME -> RING_STREAM
            SettingKey.ALARM_VOLUME -> ALARM_STREAM
            SettingKey.NOTIFICATION_VOLUME -> NOTIFICATION_STREAM
            else -> MEDIA_STREAM
        }

    /** A stream's level and its maximum. */
    private fun volume(stream: String): Pair<Int, Int> {
        val output = run(listOf("cmd", "media_session", "volume", "--stream", stream, "--get"))
        val match = checkNotNull(VOLUME.find(output)) { "Could not read the volume: ${output.trim().take(MAX_OUTPUT)}" }
        return match.groupValues[1].toInt() to match.groupValues[3].toInt()
    }

    /** Runs the first command that succeeds; older Android versions lack some `cmd` services. */
    private fun firstWorking(vararg commands: List<String>) {
        var last: Exception? = null
        for (command in commands) {
            try {
                run(command)
                return
            } catch (e: IllegalStateException) {
                last = e
            }
        }
        throw checkNotNull(last)
    }

    internal companion object {
        const val MAX_BRIGHTNESS = 255
        const val MEDIA_STREAM = "3"
        const val RING_STREAM = "2"
        const val ALARM_STREAM = "4"
        const val NOTIFICATION_STREAM = "5"
        private const val MAX_OUTPUT = 200

        /** `cmd media_session volume --get` prints, for example, "volume is 7 in range [0..15]". */
        val VOLUME = Regex("volume is (\\d+) in range \\[(\\d+)\\.\\.(\\d+)]")
    }
}
