package app.pocketpilot.capability.api.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdbQueryTest {
    private fun ok(command: String) = AdbQuery.check(AdbQuery.split(command))

    private fun refused(command: String) {
        assertFailsWith<IllegalArgumentException>(command) { AdbQuery.check(AdbQuery.split(command)) }
    }

    @Test
    fun `read-only commands pass`() {
        listOf(
            "dumpsys battery",
            "dumpsys -l",
            "dumpsys meminfo com.example.app",
            "dumpsys batterystats --charged",
            "getprop ro.build.version.security_patch",
            "settings get global airplane_mode_on",
            "settings list secure",
            "pm list packages -3",
            "pm path com.example.app",
            "cmd wifi status",
            "cmd package list packages",
            "cmd deviceidle whitelist",
            "am get-standby-bucket com.example.app",
            "appops get com.example.app",
            "wm size",
            "top -b -n 1 -m 10",
            "ps -A",
            "df -h",
            "ip addr",
            "ip route get 1.1.1.1",
            "ping -c 3 -W 2 1.1.1.1",
            "cat /sys/class/power_supply/battery/temp",
            "cat /proc/meminfo",
            "ls /sys/class/thermal/",
        ).forEach(::ok)
    }

    @Test
    fun `commands that change the phone or read personal data are refused`() {
        listOf(
            "dumpsys",
            "dumpsys notification",
            "dumpsys clipboard",
            "dumpsys account",
            "dumpsys location",
            "settings put global adb_enabled 0",
            "settings delete secure x",
            "pm uninstall com.example.app",
            "pm clear com.example.app",
            "cmd wifi set-wifi-enabled disabled",
            "cmd activity dumpheap 123 /sdcard/heap",
            "cmd notification list",
            "cmd deviceidle whitelist +com.example.app",
            "am start -n com.example/.Main",
            "am force-stop com.example.app",
            "appops set com.example.app CAMERA allow",
            "wm size 1080x1920",
            "ip link set wlan0 down",
            "ip route flush table main",
            "ping 1.1.1.1",
            "ping -c 1000 1.1.1.1",
            "ping -f -c 3 1.1.1.1",
            "cat /data/data/com.example.app/shared_prefs/x.xml",
            "cat /proc/../data/x",
            "ls /sdcard",
            "logcat -d",
            "sh -c id",
            "reboot",
            "rm -rf /sdcard",
            "dumpsys battery;reboot",
            "input tap 1 1",
        ).forEach(::refused)
    }

    @Test
    fun `logs are a separate allowlist`() {
        AdbQuery.checkLogs(listOf("logcat", "-d", "-v", "time", "-T", "1760212345.000", "*:W", "--pid=4242"))
        AdbQuery.checkLogs(listOf("pidof", "com.example.app"))
        AdbQuery.checkLogs(listOf("dumpsys", "dropbox", "--print", "data_app_anr"))
        assertFailsWith<IllegalArgumentException> { AdbQuery.checkLogs(listOf("logcat", "-c")) }
        assertFailsWith<IllegalArgumentException> { AdbQuery.checkLogs(listOf("logcat", "-d", "-f", "/sdcard/x")) }
        assertFailsWith<IllegalArgumentException> { AdbQuery.checkLogs(listOf("logcat")) }
        assertTrue(AdbQuery.allowed(listOf("pidof", "com.example.app")))
        assertFalse(AdbQuery.allowed(listOf("pm", "uninstall", "com.example.app")))
    }

    @Test
    fun `adb shell prefixes are dropped`() {
        assertEquals(listOf("dumpsys", "battery"), AdbQuery.split("adb shell  dumpsys battery "))
        assertEquals(listOf("getprop"), AdbQuery.split("shell getprop"))
    }
}
