package app.pocketpilot.capability.shizuku

import app.pocketpilot.capability.api.settings.SettingKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SettingCommandsTest {
    private val ran = mutableListOf<String>()
    private val outputs = mutableMapOf<String, String>()
    private val failing = mutableSetOf<String>()

    private val commands =
        SettingCommands { argv ->
            val line = argv.joinToString(" ")
            ran += line
            check(failing.none { line.startsWith(it) }) { "failed: $line" }
            outputs[line].orEmpty()
        }

    @Test
    fun `reads and sets dark mode with uimode`() {
        outputs["cmd uimode night"] = "Night mode: yes\n"
        assertEquals("on", commands.get(SettingKey.DARK_MODE))
        commands.set(SettingKey.DARK_MODE, "off")
        assertEquals("cmd uimode night no", ran.last())
    }

    @Test
    fun `falls back to svc when cmd wifi is missing`() {
        failing += "cmd wifi"
        commands.set(SettingKey.WIFI, "on")
        assertEquals(listOf("cmd wifi set-wifi-enabled enabled", "svc wifi enable"), ran)
    }

    @Test
    fun `scales brightness and volume to percent`() {
        outputs["settings get system screen_brightness_mode"] = "0"
        outputs["settings get system screen_brightness"] = "128"
        assertEquals("50", commands.get(SettingKey.BRIGHTNESS))
        commands.set(SettingKey.BRIGHTNESS, "100")
        assertEquals("settings put system screen_brightness 255", ran.last())

        outputs["cmd media_session volume --stream 3 --get"] = "volume is 5 in range [0..15]"
        assertEquals("33", commands.get(SettingKey.VOLUME))
        commands.set(SettingKey.VOLUME, "60")
        assertEquals("cmd media_session volume --stream 3 --set 9", ran.last())
    }

    @Test
    fun `reports do not disturb and rotation`() {
        outputs["settings get global zen_mode"] = "1"
        outputs["settings get system accelerometer_rotation"] = "0"
        assertEquals("on", commands.get(SettingKey.DND))
        assertEquals("locked", commands.get(SettingKey.ROTATION))
    }

    @Test
    fun `refuses values the key does not take`() {
        assertFailsWith<IllegalArgumentException> { commands.set(SettingKey.WIFI, "auto") }
        assertFailsWith<IllegalArgumentException> { commands.set(SettingKey.VOLUME, "150") }
    }
}
