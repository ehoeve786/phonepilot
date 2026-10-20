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
import android.content.res.Configuration
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.location.LocationManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.provider.Telephony
import android.telephony.TelephonyManager
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import app.pocketpilot.capability.accessibility.A11yBridge
import app.pocketpilot.capability.api.shell.SystemReader
import app.pocketpilot.capability.notifications.ListenerNotificationSource
import app.pocketpilot.capability.shizuku.ShizukuState
import app.pocketpilot.core.automation.Place
import app.pocketpilot.core.automation.Rule
import app.pocketpilot.core.automation.TriggerEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import java.util.TimeZone

/**
 * Turns what happens on the phone into [TriggerEvent]s: system broadcasts, network and audio device
 * callbacks, the front app from the Accessibility service and notifications from the listener.
 * Registered only while at least one rule is on; sensors, location, calls, logs, the calendar and Home
 * Assistant only while a rule uses them (see [use]).
 */
class TriggerSources(
    private val context: Context,
    private val phone: PhoneState,
    private val scope: CoroutineScope,
    /** Apps on the deny list never trigger rules, so their content cannot leak into actions. */
    private val hidden: (String) -> Boolean,
    private val places: PlaceWatcher,
    private val calendars: Calendars,
    private val homeAssistant: HomeAssistant,
    logs: SystemReader,
    /** Text messages are read in the sideload build only. */
    private val sideload: Boolean,
    private val shizukuState: StateFlow<ShizukuState>,
    private val emit: (TriggerEvent) -> Unit,
) {
    private val sensors = SensorWatcher(context, emit)
    private var addressCallback: ConnectivityManager.NetworkCallback? = null
    private var lastAddresses: Set<String>? = null
    private val enabledApps = HashMap<String, Boolean>()
    private val logWatcher = LogWatcher(logs, scope, hidden, emit)
    private var callReceiver: BroadcastReceiver? = null
    private var smsReceiver: BroadcastReceiver? = null
    private var actionReceiver: Pair<Set<String>, BroadcastReceiver>? = null
    private var mediaWatch: MediaWatch? = null
    private var screenText: Pair<Set<String>, Job>? = null

    private var receiver: BroadcastReceiver? = null
    private var packageReceiver: BroadcastReceiver? = null
    private val callbacks = mutableListOf<ConnectivityManager.NetworkCallback>()
    private var audioCallback: AudioDeviceCallback? = null
    private val jobs = mutableListOf<Job>()
    private var lastLevel: Int? = null
    private var internetOn: Boolean? = null
    private val hardware = HardwareWatch(context, emit)
    private var lastOrientation: String? = null
    private var lastDark: Boolean? = null
    private var lastGps: Boolean? = null
    private var lastCelsius: Int? = null

    @Synchronized
    fun start() {
        if (receiver != null) return
        lastLevel = phone.batteryLevel()
        lastCelsius = null
        context.resources.configuration.let {
            lastOrientation = orientationOf(it)
            lastDark = darkOf(it)
        }
        lastGps = gpsOn()
        receiver = systemReceiver().also { register(it, systemFilter()) }
        packageReceiver =
            packageReceiver().also {
                register(
                    it,
                    IntentFilter().apply {
                        addAction(Intent.ACTION_PACKAGE_ADDED)
                        addAction(Intent.ACTION_PACKAGE_REPLACED)
                        addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED)
                        addAction(Intent.ACTION_PACKAGE_CHANGED)
                        addDataScheme("package")
                    },
                )
            }
        watchNetworks()
        watchHeadsets()
        jobs += scope.launch { watchFrontApp() }
        jobs +=
            scope.launch {
                var previous = shizukuState.value
                shizukuState.collect { now ->
                    if (previous == ShizukuState.CONNECTED && now == ShizukuState.NOT_RUNNING) emit(TriggerEvent("shizuku.stopped"))
                    previous = now
                }
            }
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

    /**
     * Starts the sources that cost battery or need a permission only while an enabled rule in [rules]
     * uses them, and stops the rest. Called whenever the rules change, after [start].
     */
    @Synchronized
    fun use(rules: List<Rule>) {
        val triggers = rules.flatMap { it.triggers }
        val types = triggers.map { it.type }.toSet()

        fun params(type: String) = triggers.filter { it.type == type }.map { it.params }

        places.watch(params("location.place").mapNotNull(Place::from))
        sensors.watch(
            shake = "sensor.shake" in types,
            flip = "sensor.flip" in types,
            proximity = "sensor.proximity" in types,
            thresholds = params("sensor.reading").mapNotNull(::sensorThresholdOf),
        )
        hardware.watch(
            torchOrCamera = "torch.changed" in types || "camera.inuse" in types,
            settingsWatch = "autorotate.changed" in types || "accessibility.changed" in types,
            photosWatch = "photo.taken" in types,
        )
        watchKeys("volume.button" in types || "media.button" in types)
        watchAddresses("ip.changed" in types)
        // An app's state is judged against the state seen when the rule started, so the first change is not missed.
        params("app.enabled").mapNotNull { it["package"]?.trim()?.takeIf(String::isNotEmpty) }.forEach { pkg ->
            enabledApps.getOrPut(pkg) { appEnabled(pkg) }
        }
        logWatcher.watch(params("log.line").mapNotNull { it["text"]?.trim()?.takeIf(String::isNotEmpty) }.toSet(), "app.crash" in types)
        calendars.watch(if ("calendar.event" in types) emit else null)
        homeAssistant.watch(
            params("ha.state").mapNotNull { it["entity"]?.trim()?.takeIf(String::isNotEmpty) }.toSet(),
        ) { entity, state, previous ->
            emit(TriggerEvent("ha.state", mapOf("entity" to entity, "state" to state, "previous" to previous)))
        }
        if ("call.changed" in types) watchCalls() else unregister(callReceiver).also { callReceiver = null }
        if ("sms.received" in types && sideload) watchSms() else unregister(smsReceiver).also { smsReceiver = null }
        watchActions(params("intent.action").mapNotNull { it["action"]?.trim()?.takeIf(String::isNotEmpty) }.toSet())
        if ("media.changed" in types) {
            if (mediaWatch == null) mediaWatch = MediaWatch(context, hidden, emit).also { it.start() }
        } else {
            mediaWatch?.stop()
            mediaWatch = null
        }
        watchScreenText(params("screen.text").mapNotNull { it["text"]?.trim()?.takeIf(String::isNotEmpty) }.toSet())
    }

    @Synchronized
    fun stop() {
        places.watch(emptyList())
        sensors.stop()
        hardware.stop()
        watchKeys(false)
        watchAddresses(false)
        logWatcher.stop()
        calendars.watch(null)
        homeAssistant.stop()
        listOfNotNull(callReceiver, smsReceiver, actionReceiver?.second).forEach(::unregister)
        callReceiver = null
        smsReceiver = null
        actionReceiver = null
        mediaWatch?.stop()
        mediaWatch = null
        screenText?.second?.cancel()
        screenText = null
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

    private fun unregister(receiver: BroadcastReceiver?) {
        if (receiver != null) runCatching { context.unregisterReceiver(receiver) }
    }

    /**
     * Ringing, answered, ended and missed, from the call state broadcasts. The number comes with
     * call log access, in the sideload build; Android then sends each broadcast twice, once without it.
     */
    private fun watchCalls() {
        if (callReceiver != null) return
        var last = TelephonyManager.EXTRA_STATE_IDLE
        var number = ""
        var answered = false
        val main = Handler(Looper.getMainLooper())
        var pendingRing: Runnable? = null

        fun flushRing() {
            pendingRing?.let {
                main.removeCallbacks(it)
                pendingRing = null
                it.run()
            }
        }
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return

                    @Suppress("DEPRECATION")
                    val incoming = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
                    if (state == last) {
                        // The second copy, with the number: a ringing call fires once it is known.
                        if (state == TelephonyManager.EXTRA_STATE_RINGING && number.isEmpty() && !incoming.isNullOrEmpty()) {
                            number = incoming
                            flushRing()
                            emit(call("ringing", number))
                        }
                        return
                    }
                    val before = last
                    last = state
                    when (state) {
                        TelephonyManager.EXTRA_STATE_RINGING -> {
                            number = incoming.orEmpty()
                            answered = false
                            if (number.isNotEmpty() || !granted(Manifest.permission.READ_CALL_LOG)) {
                                emit(call("ringing", number))
                            } else {
                                // A withheld number never gets the second copy, so the ring goes out after a short wait.
                                val ring = Runnable { emit(call("ringing", number)) }
                                pendingRing = ring
                                main.postDelayed(ring, WITHHELD_RING_WAIT_MS)
                            }
                        }

                        TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                            flushRing()
                            if (before == TelephonyManager.EXTRA_STATE_IDLE) number = incoming.orEmpty()
                            answered = true
                            emit(call("answered", number))
                        }

                        TelephonyManager.EXTRA_STATE_IDLE -> {
                            flushRing()
                            emit(call(if (answered) "ended" else "missed", number))
                            number = ""
                            answered = false
                        }
                    }
                }
            }
        register(receiver, IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED))
        callReceiver = receiver
    }

    private fun call(
        event: String,
        number: String,
    ) = TriggerEvent("call.changed", mapOf("event" to event, "number" to number))

    private fun watchSms() {
        if (smsReceiver != null) return
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    val parts =
                        Telephony.Sms.Intents
                            .getMessagesFromIntent(intent)
                            ?.filterNotNull()
                            .orEmpty()
                    if (parts.isEmpty()) return
                    val from = parts.first().originatingAddress.orEmpty()
                    val text = parts.joinToString("") { it.messageBody.orEmpty() }
                    emit(TriggerEvent("sms.received", mapOf("from" to from, "text" to text.take(MAX_EXTRA_VALUE))))
                }
            }
        register(receiver, IntentFilter(Telephony.Sms.Intents.SMS_RECEIVED_ACTION))
        smsReceiver = receiver
    }

    /** "Any broadcast": one receiver for every action the rules name, open to other apps. */
    private fun watchActions(actions: Set<String>) {
        if (actionReceiver?.first == actions) return
        unregister(actionReceiver?.second)
        actionReceiver = null
        if (actions.isEmpty()) return
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    val action = intent.action ?: return
                    val extras = intent.extras
                    val data =
                        extras
                            ?.keySet()
                            ?.take(MAX_EXTRAS)
                            ?.mapNotNull { key ->
                                @Suppress("DEPRECATION")
                                val value = runCatching { extras.get(key) }.getOrNull()
                                if (value is String || value is Number ||
                                    value is Boolean
                                ) {
                                    key to value.toString().take(MAX_EXTRA_VALUE)
                                } else {
                                    null
                                }
                            }?.toMap()
                            .orEmpty()
                    emit(TriggerEvent("intent.action", data + mapOf("action" to action, "data" to intent.dataString.orEmpty())))
                }
            }
        val filter = IntentFilter().apply { actions.forEach(::addAction) }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        actionReceiver = actions to receiver
    }

    /** "Text appears on screen": checks the front window's text after the screen settles. */
    @OptIn(FlowPreview::class)
    private fun watchScreenText(texts: Set<String>) {
        if (screenText?.first == texts) return
        screenText?.second?.cancel()
        screenText = null
        if (texts.isEmpty()) return
        val job =
            scope.launch {
                var shown = emptySet<String>()
                var lastPackage: String? = null
                A11yBridge.changeCount.debounce(SCREEN_SETTLE_MS).collect {
                    val (pkg, onScreen) = A11yBridge.frontWindowText() ?: return@collect
                    if (pkg != lastPackage) {
                        shown = emptySet()
                        lastPackage = pkg
                    }
                    if (pkg == context.packageName || hidden(pkg)) return@collect
                    val now =
                        texts
                            .mapNotNull { wanted -> onScreen.firstOrNull { it.contains(wanted, ignoreCase = true) }?.let { wanted to it } }
                            .toMap()
                    (now.keys - shown).forEach { wanted ->
                        emit(TriggerEvent("screen.text", mapOf("text" to now.getValue(wanted).take(MAX_EXTRA_VALUE), "package" to pkg)))
                    }
                    shown = now.keys
                }
            }
        screenText = texts to job
    }

    private fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

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
                Intent.ACTION_DOCK_EVENT,
                Intent.ACTION_CONFIGURATION_CHANGED,
                LocationManager.PROVIDERS_CHANGED_ACTION,
                NfcAdapter.ACTION_ADAPTER_STATE_CHANGED,
                UsbManager.ACTION_USB_DEVICE_ATTACHED,
                UsbManager.ACTION_USB_DEVICE_DETACHED,
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
                        // The temperature is in tenths of a degree; the first reading only sets the baseline.
                        val tenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                        if (tenths != Int.MIN_VALUE) {
                            val celsius = tenths / 10
                            val previous = lastCelsius
                            lastCelsius = celsius
                            if (previous != null && celsius != previous) {
                                emit(TriggerEvent("battery.temperature", mapOf("celsius" to celsius.toString())))
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

                    Intent.ACTION_DOCK_EVENT -> {
                        val docked = intent.getIntExtra(Intent.EXTRA_DOCK_STATE, Intent.EXTRA_DOCK_STATE_UNDOCKED)
                        emit(state("dock.changed", docked != Intent.EXTRA_DOCK_STATE_UNDOCKED))
                    }

                    Intent.ACTION_CONFIGURATION_CHANGED -> {
                        configurationChanged(context.resources.configuration)
                    }

                    LocationManager.PROVIDERS_CHANGED_ACTION -> {
                        // Fires for every provider; the GPS state is reported only when it changes.
                        val on = gpsOn()
                        if (on != lastGps) {
                            lastGps = on
                            emit(state("gps.changed", on))
                        }
                    }

                    NfcAdapter.ACTION_ADAPTER_STATE_CHANGED -> {
                        when (intent.getIntExtra(NfcAdapter.EXTRA_ADAPTER_STATE, NfcAdapter.STATE_OFF)) {
                            NfcAdapter.STATE_ON -> emit(state("nfc.changed", true))
                            NfcAdapter.STATE_OFF -> emit(state("nfc.changed", false))
                        }
                    }

                    UsbManager.ACTION_USB_DEVICE_ATTACHED, UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        usbDevice(intent)
                    }
                }
            }
        }

    private fun configurationChanged(config: Configuration) {
        val orientation = orientationOf(config)
        if (orientation != lastOrientation) {
            lastOrientation = orientation
            emit(TriggerEvent("orientation.changed", mapOf("orientation" to orientation)))
        }
        val dark = darkOf(config)
        if (dark != lastDark) {
            lastDark = dark
            emit(state("dark.changed", dark))
        }
    }

    private fun gpsOn(): Boolean = context.getSystemService(LocationManager::class.java).isProviderEnabled(LocationManager.GPS_PROVIDER)

    private fun usbDevice(intent: Intent) {
        val device: UsbDevice =
            (
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }
            ) ?: return
        val attached = intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED
        val name = device.productName ?: device.deviceName
        emit(TriggerEvent("usb.device", mapOf("state" to if (attached) "connected" else "disconnected", "name" to name)))
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
                if (intent.action == Intent.ACTION_PACKAGE_CHANGED) {
                    appEnabledChanged(pkg)
                    return
                }
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

    private fun watchKeys(on: Boolean) {
        A11yBridge.listenToKeys(if (on) ::keyPressed else null)
    }

    /** Volume and media keys: the key-down of each press, not the repeats while it is held. */
    private fun keyPressed(event: KeyEvent) {
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) return
        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> emit(TriggerEvent("volume.button", mapOf("button" to "up")))
            KeyEvent.KEYCODE_VOLUME_DOWN -> emit(TriggerEvent("volume.button", mapOf("button" to "down")))
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> emit(TriggerEvent("media.button", mapOf("button" to "play_pause")))
            KeyEvent.KEYCODE_MEDIA_NEXT -> emit(TriggerEvent("media.button", mapOf("button" to "next")))
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> emit(TriggerEvent("media.button", mapOf("button" to "previous")))
            else -> Unit
        }
    }

    private fun appEnabled(pkg: String): Boolean {
        val setting = context.packageManager.getApplicationEnabledSetting(pkg)
        return setting == PackageManager.COMPONENT_ENABLED_STATE_ENABLED || setting == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
    }

    /** Reports an app turned on or off, for the apps a rule watches; other apps are not reported. */
    private fun appEnabledChanged(pkg: String) {
        val on = appEnabled(pkg)
        val before = enabledApps.put(pkg, on) ?: return
        if (before != on) emit(TriggerEvent("app.enabled", mapOf("package" to pkg, "state" to PhoneState.onOff(on))))
    }

    /** Reports the phone's address when the default network's addresses change; the first report is not a change. */
    private fun watchAddresses(on: Boolean) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        if (!on) {
            addressCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
            addressCallback = null
            lastAddresses = null
            return
        }
        if (addressCallback != null) return
        addressCallback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onLinkPropertiesChanged(
                    network: Network,
                    linkProperties: LinkProperties,
                ) {
                    val addresses = linkProperties.linkAddresses.mapNotNull { it.address?.hostAddress }.toSet()
                    val before = lastAddresses
                    lastAddresses = addresses
                    if (before != null && addresses != before) {
                        val shown = addresses.firstOrNull { !it.contains(':') } ?: addresses.firstOrNull().orEmpty()
                        emit(TriggerEvent("ip.changed", mapOf("address" to shown)))
                    }
                }
            }.also { cm.registerDefaultNetworkCallback(it) }
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
        fun orientationOf(config: Configuration): String =
            if (config.orientation == Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait"

        fun darkOf(config: Configuration): Boolean = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        /** How long a withheld call's ring waits for the number before it is reported without one. */
        const val WITHHELD_RING_WAIT_MS = 1_500L

        val IGNORED = setOf("com.android.systemui")
        const val SETTLE_MS = 2_000L
        const val SCREEN_SETTLE_MS = 1_000L
        const val MAX_EXTRAS = 20
        const val MAX_EXTRA_VALUE = 2_000
    }
}
