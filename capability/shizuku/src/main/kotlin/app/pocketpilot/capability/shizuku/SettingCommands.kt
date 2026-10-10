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

            SettingKey.VOLUME -> {
                val (level, max) = mediaVolume()
                if (max == 0) "0" else (level * 100.0 / max).roundToInt().toString()
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

            SettingKey.VOLUME -> {
                val (_, max) = mediaVolume()
                val level = (value.toInt() * max / 100.0).roundToInt().coerceIn(0, max)
                run(listOf("cmd", "media_session", "volume", "--stream", MEDIA_STREAM, "--set", level.toString()))
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

    /** The media stream's level and its maximum. */
    private fun mediaVolume(): Pair<Int, Int> {
        val output = run(listOf("cmd", "media_session", "volume", "--stream", MEDIA_STREAM, "--get"))
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
        private const val MAX_OUTPUT = 200

        /** `cmd media_session volume --get` prints, for example, "volume is 7 in range [0..15]". */
        val VOLUME = Regex("volume is (\\d+) in range \\[(\\d+)\\.\\.(\\d+)]")
    }
}
