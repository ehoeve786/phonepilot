package app.pocketpilot.automation

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.PowerManager
import android.provider.Settings
import app.pocketpilot.capability.accessibility.A11yBridge
import app.pocketpilot.capability.shizuku.ShizukuConnection
import app.pocketpilot.capability.shizuku.ShizukuShellRunner
import app.pocketpilot.capability.shizuku.ShizukuState
import app.pocketpilot.core.automation.StateKeys
import app.pocketpilot.server.http.McpHttpServer
import app.pocketpilot.server.http.ServerState
import java.util.concurrent.ConcurrentHashMap

/**
 * Answers the [StateKeys] that conditions read, from Android's own APIs where they need no extra
 * permission and from Shizuku where they would (the Wi-Fi name without location access).
 */
class PhoneState(
    private val context: Context,
    private val shizuku: ShizukuConnection,
    private val shell: ShizukuShellRunner,
    private val server: () -> McpHttpServer,
) {
    /** Bluetooth devices connected now, by address; filled from the connection broadcasts. */
    val bluetoothDevices = ConcurrentHashMap<String, String>()

    /** The last Wi-Fi name seen, so "Wi-Fi disconnects" can say which network it left. */
    @Volatile var lastSsid: String? = null

    suspend fun read(key: String): String? =
        when (key) {
            StateKeys.BATTERY_LEVEL -> batteryLevel()?.toString()
            StateKeys.PLUGGED -> onOff(plugSource() != null)
            StateKeys.BATTERY_SAVER -> onOff(power.isPowerSaveMode)
            StateKeys.SCREEN_ON -> onOff(power.isInteractive)
            StateKeys.LOCKED -> onOff(context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
            StateKeys.WIFI_ENABLED -> onOff(wifiEnabled())
            StateKeys.WIFI_SSID -> wifiSsid()
            StateKeys.BLUETOOTH_ENABLED -> onOff(globalInt(Settings.Global.BLUETOOTH_ON) == 1)
            StateKeys.BLUETOOTH_DEVICES -> bluetoothDevices.values.joinToString("\n")
            StateKeys.INTERNET -> onOff(hasTransportOrCapability(capability = NetworkCapabilities.NET_CAPABILITY_VALIDATED))
            StateKeys.VPN -> onOff(hasTransportOrCapability(transport = NetworkCapabilities.TRANSPORT_VPN))
            StateKeys.AIRPLANE -> onOff(globalInt(Settings.Global.AIRPLANE_MODE_ON) == 1)
            StateKeys.RINGER -> ringerName(audio.ringerMode)
            StateKeys.DND -> onOff(dndOn())
            StateKeys.HEADSET -> onOff(headsetConnected())
            StateKeys.FOREGROUND -> A11yBridge.frontPackage.value
            StateKeys.DARK_MODE -> onOff(darkMode())
            StateKeys.SHIZUKU -> onOff(shizuku.state.value == ShizukuState.CONNECTED)
            StateKeys.ACCESSIBILITY -> onOff(A11yBridge.connected.value)
            StateKeys.SERVER -> onOff(server().serverState.value is ServerState.Running)
            StateKeys.MUSIC -> onOff(audio.isMusicActive)
            StateKeys.CALL -> callState()
            else -> null
        }

    private val power get() = context.getSystemService(PowerManager::class.java)
    private val audio get() = context.getSystemService(AudioManager::class.java)

    fun batteryLevel(): Int? {
        val level = context.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return level.takeIf { it in 0..100 }
    }

    /** "ac", "usb" or "wireless" while plugged in, else null. */
    fun plugSource(intent: Intent? = null): String? {
        val battery = intent ?: context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        return when (battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
            BatteryManager.BATTERY_PLUGGED_AC -> "ac"
            BatteryManager.BATTERY_PLUGGED_USB -> "usb"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
            BatteryManager.BATTERY_PLUGGED_DOCK -> "ac"
            else -> null
        }
    }

    fun wifiEnabled(): Boolean = context.getSystemService(WifiManager::class.java).isWifiEnabled

    /**
     * The connected Wi-Fi network's name. Android hides it from apps without location access, so it
     * comes from `cmd wifi status` through Shizuku; null when not on Wi-Fi or neither works.
     */
    suspend fun wifiSsid(): String? {
        if (!hasTransportOrCapability(transport = NetworkCapabilities.TRANSPORT_WIFI)) return null
        if (shell.available.value) {
            val out = runCatching { shell.run("cmd wifi status", SHELL_TIMEOUT_MS).stdout }.getOrNull().orEmpty()
            SSID.find(out)?.groupValues?.get(1)?.let {
                lastSsid = it
                return it
            }
        }
        @Suppress("DEPRECATION")
        val ssid =
            context
                .getSystemService(WifiManager::class.java)
                .connectionInfo
                ?.ssid
                ?.trim('"')
                ?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
        if (ssid != null) lastSsid = ssid
        return ssid
    }

    fun hasTransportOrCapability(
        transport: Int? = null,
        capability: Int? = null,
    ): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return (transport == null || caps.hasTransport(transport)) && (capability == null || caps.hasCapability(capability))
    }

    fun dndOn(): Boolean =
        context.getSystemService(NotificationManager::class.java).currentInterruptionFilter.let {
            it != NotificationManager.INTERRUPTION_FILTER_ALL && it != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        }

    /** From the audio mode, which needs no phone permission and also counts calls in calling apps. */
    private fun callState(): String =
        when (audio.mode) {
            AudioManager.MODE_RINGTONE -> "ringing"
            AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION, AudioManager.MODE_CALL_SCREENING -> "in_call"
            else -> "idle"
        }

    fun headsetConnected(): Boolean = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in HEADSET_TYPES }

    private fun darkMode(): Boolean =
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    private fun globalInt(name: String): Int = Settings.Global.getInt(context.contentResolver, name, 0)

    companion object {
        private const val SHELL_TIMEOUT_MS = 5_000L
        private val SSID = Regex("""connected to "([^"]+)"""", RegexOption.IGNORE_CASE)

        val HEADSET_TYPES =
            setOf(
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
            )

        fun onOff(value: Boolean): String = if (value) "on" else "off"

        fun ringerName(mode: Int): String =
            when (mode) {
                AudioManager.RINGER_MODE_SILENT -> "silent"
                AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
                else -> "normal"
            }
    }
}
