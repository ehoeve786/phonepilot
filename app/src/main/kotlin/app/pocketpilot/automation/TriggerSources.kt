package app.pocketpilot.automation

import android.Manifest
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.ContextCompat
import app.pocketpilot.capability.accessibility.A11yBridge
import app.pocketpilot.capability.notifications.ListenerNotificationSource
import app.pocketpilot.core.automation.TriggerEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.TimeZone

/**
 * Turns what happens on the phone into [TriggerEvent]s: system broadcasts, network and audio device
 * callbacks, the front app from the Accessibility service and notifications from the listener.
 * Registered only while at least one rule is on.
 */
class TriggerSources(
    private val context: Context,
    private val phone: PhoneState,
    private val scope: CoroutineScope,
    /** Apps on the deny list never trigger rules, so their content cannot leak into actions. */
    private val hidden: (String) -> Boolean,
    private val emit: (TriggerEvent) -> Unit,
) {
    private var receiver: BroadcastReceiver? = null
    private var packageReceiver: BroadcastReceiver? = null
    private val callbacks = mutableListOf<ConnectivityManager.NetworkCallback>()
    private var audioCallback: AudioDeviceCallback? = null
    private val jobs = mutableListOf<Job>()
    private var lastLevel: Int? = null
    private var internetOn: Boolean? = null

    @Synchronized
    fun start() {
        if (receiver != null) return
        lastLevel = phone.batteryLevel()
        receiver = systemReceiver().also { register(it, systemFilter()) }
        packageReceiver =
            packageReceiver().also {
                register(
                    it,
                    IntentFilter().apply {
                        addAction(Intent.ACTION_PACKAGE_ADDED)
                        addAction(Intent.ACTION_PACKAGE_REPLACED)
                        addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED)
                        addDataScheme("package")
                    },
                )
            }
        watchNetworks()
        watchHeadsets()
        jobs += scope.launch { watchFrontApp() }
        jobs +=
            scope.launch {
                ListenerNotificationSource.events.collect { change ->
                    val n = change.notification
                    if (hidden(n.packageName) || n.packageName == context.packageName) return@collect
                    emit(
                        TriggerEvent(
                            if (change.removed) "notification.removed" else "notification.posted",
                            mapOf("package" to n.packageName, "title" to n.title.orEmpty(), "text" to n.text.orEmpty(), "key" to n.key),
                        ),
                    )
                }
            }
    }

    @Synchronized
    fun stop() {
        listOfNotNull(receiver, packageReceiver).forEach { runCatching { context.unregisterReceiver(it) } }
        receiver = null
        packageReceiver = null
        val cm = context.getSystemService(ConnectivityManager::class.java)
        callbacks.forEach { runCatching { cm.unregisterNetworkCallback(it) } }
        callbacks.clear()
        audioCallback?.let { context.getSystemService(AudioManager::class.java).unregisterAudioDeviceCallback(it) }
        audioCallback = null
        jobs.forEach { it.cancel() }
        jobs.clear()
    }

    private fun register(
        receiver: BroadcastReceiver,
        filter: IntentFilter,
    ) = ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

    private fun systemFilter() =
        IntentFilter().apply {
            listOf(
                Intent.ACTION_SCREEN_ON,
                Intent.ACTION_SCREEN_OFF,
                Intent.ACTION_USER_PRESENT,
                Intent.ACTION_POWER_CONNECTED,
                Intent.ACTION_POWER_DISCONNECTED,
                Intent.ACTION_BATTERY_CHANGED,
                Intent.ACTION_AIRPLANE_MODE_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED,
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED,
                NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED,
                AudioManager.RINGER_MODE_CHANGED_ACTION,
                WifiManager.WIFI_STATE_CHANGED_ACTION,
                BluetoothAdapter.ACTION_STATE_CHANGED,
                BluetoothDevice.ACTION_ACL_CONNECTED,
                BluetoothDevice.ACTION_ACL_DISCONNECTED,
            ).forEach(::addAction)
        }

    private fun systemReceiver() =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_ON -> {
                        emit(TriggerEvent("screen.on"))
                    }

                    Intent.ACTION_SCREEN_OFF -> {
                        emit(TriggerEvent("screen.off"))
                    }

                    Intent.ACTION_USER_PRESENT -> {
                        emit(TriggerEvent("screen.unlocked"))
                    }

                    Intent.ACTION_POWER_CONNECTED -> {
                        val source = phone.plugSource() ?: "ac"
                        emit(TriggerEvent("power.connected", mapOf("source" to source, "level" to level())))
                    }

                    Intent.ACTION_POWER_DISCONNECTED -> {
                        emit(TriggerEvent("power.disconnected", mapOf("level" to level())))
                    }

                    Intent.ACTION_BATTERY_CHANGED -> {
                        val raw = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                        if (raw >= 0 && scale > 0) {
                            val level = raw * 100 / scale
                            if (level != lastLevel) {
                                lastLevel = level
                                emit(TriggerEvent("battery.level", mapOf("level" to level.toString())))
                            }
                        }
                    }

                    Intent.ACTION_AIRPLANE_MODE_CHANGED -> {
                        emit(state("airplane.changed", intent.getBooleanExtra("state", false)))
                    }

                    Intent.ACTION_TIMEZONE_CHANGED -> {
                        val zone = intent.getStringExtra("time-zone") ?: TimeZone.getDefault().id
                        emit(TriggerEvent("time.timezone", mapOf("zone" to zone)))
                    }

                    PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> {
                        emit(state("battery.saver", context.getSystemService(PowerManager::class.java).isPowerSaveMode))
                    }

                    NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED -> {
                        emit(state("dnd.changed", phone.dndOn()))
                    }

                    AudioManager.RINGER_MODE_CHANGED_ACTION -> {
                        val mode = intent.getIntExtra(AudioManager.EXTRA_RINGER_MODE, AudioManager.RINGER_MODE_NORMAL)
                        emit(TriggerEvent("ringer.changed", mapOf("mode" to PhoneState.ringerName(mode))))
                    }

                    WifiManager.WIFI_STATE_CHANGED_ACTION -> {
                        when (intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN)) {
                            WifiManager.WIFI_STATE_ENABLED -> emit(state("wifi.state", true))
                            WifiManager.WIFI_STATE_DISABLED -> emit(state("wifi.state", false))
                        }
                    }

                    BluetoothAdapter.ACTION_STATE_CHANGED -> {
                        when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                            BluetoothAdapter.STATE_ON -> {
                                emit(state("bluetooth.state", true))
                            }

                            BluetoothAdapter.STATE_OFF -> {
                                phone.bluetoothDevices.clear()
                                emit(state("bluetooth.state", false))
                            }
                        }
                    }

                    BluetoothDevice.ACTION_ACL_CONNECTED, BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                        bluetoothDevice(intent)
                    }
                }
            }
        }

    private fun bluetoothDevice(intent: Intent) {
        val device: BluetoothDevice =
            (
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }
            ) ?: return
        val address = runCatching { device.address }.getOrNull().orEmpty()
        // The name needs the Nearby devices permission; without it the address stands in.
        val name =
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            ) {
                runCatching { device.name }.getOrNull() ?: address
            } else {
                address
            }
        val connected = intent.action == BluetoothDevice.ACTION_ACL_CONNECTED
        if (connected) phone.bluetoothDevices[address] = name else phone.bluetoothDevices.remove(address)
        emit(
            TriggerEvent(
                "bluetooth.device",
                mapOf("event" to if (connected) "connected" else "disconnected", "name" to name, "address" to address),
            ),
        )
    }

    private fun packageReceiver() =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                val pkg = intent.data?.schemeSpecificPart ?: return
                val replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
                val event =
                    when (intent.action) {
                        Intent.ACTION_PACKAGE_ADDED -> if (replacing) return else "installed"
                        Intent.ACTION_PACKAGE_REPLACED -> "updated"
                        Intent.ACTION_PACKAGE_FULLY_REMOVED -> "removed"
                        else -> return
                    }
                emit(TriggerEvent("app.package", mapOf("event" to event, "package" to pkg)))
            }
        }

    private fun watchNetworks() {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val main = Handler(Looper.getMainLooper())
        // Registering reports the networks already up; those are not changes.
        val since = SystemClock.elapsedRealtime()
        val settled = { SystemClock.elapsedRealtime() - since > SETTLE_MS }

        val wifi =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (!settled()) {
                        scope.launch { phone.wifiSsid() }
                        return
                    }
                    scope.launch {
                        val ssid = phone.wifiSsid().orEmpty()
                        emit(TriggerEvent("wifi.connected", mapOf("ssid" to ssid)))
                    }
                }

                override fun onLost(network: Network) {
                    emit(TriggerEvent("wifi.disconnected", mapOf("ssid" to phone.lastSsid.orEmpty())))
                }
            }
        cm.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), wifi, main)
        callbacks += wifi

        val vpn =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (settled()) emit(state("vpn.changed", true))
                }

                override fun onLost(network: Network) = emit(state("vpn.changed", false))
            }
        cm.registerNetworkCallback(
            NetworkRequest
                .Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build(),
            vpn,
            main,
        )
        callbacks += vpn

        internetOn = phone.hasTransportOrCapability(capability = NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val internet =
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(
                    network: Network,
                    caps: NetworkCapabilities,
                ) = internet(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))

                override fun onLost(network: Network) = internet(false)
            }
        cm.registerDefaultNetworkCallback(internet, main)
        callbacks += internet
    }

    private fun internet(on: Boolean) {
        if (internetOn == on) return
        internetOn = on
        emit(state("internet.changed", on))
    }

    private fun watchHeadsets() {
        val audio = context.getSystemService(AudioManager::class.java)
        var first = true
        val callback =
            object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
                    // The callback reports the devices already there when it is registered.
                    if (first) {
                        first = false
                        return
                    }
                    added.firstOrNull { it.type in PhoneState.HEADSET_TYPES }?.let {
                        emit(TriggerEvent("headset.changed", mapOf("state" to "connected", "name" to it.productName.toString())))
                    }
                }

                override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
                    removed.firstOrNull { it.type in PhoneState.HEADSET_TYPES }?.let {
                        emit(TriggerEvent("headset.changed", mapOf("state" to "disconnected", "name" to it.productName.toString())))
                    }
                }
            }
        audio.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        audioCallback = callback
    }

    /** App opened and closed, from the windows the Accessibility service sees; the keyboard and system UI are not apps. */
    private suspend fun watchFrontApp() {
        var front: String? = null
        A11yBridge.frontPackage.collect { pkg ->
            if (pkg == null || pkg == front || pkg in IGNORED || pkg == keyboardPackage()) return@collect
            front?.let { if (!hidden(it)) emit(TriggerEvent("app.closed", mapOf("package" to it))) }
            front = pkg
            if (!hidden(pkg)) emit(TriggerEvent("app.opened", mapOf("package" to pkg)))
        }
    }

    private fun keyboardPackage(): String? =
        Settings.Secure
            .getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.substringBefore('/')

    private fun level(): String = phone.batteryLevel()?.toString().orEmpty()

    private fun state(
        type: String,
        on: Boolean,
    ) = TriggerEvent(type, mapOf("state" to PhoneState.onOff(on)))

    private companion object {
        val IGNORED = setOf("com.android.systemui")
        const val SETTLE_MS = 2_000L
    }
}
