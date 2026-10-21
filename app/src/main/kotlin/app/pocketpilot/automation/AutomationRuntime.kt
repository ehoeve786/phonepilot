package app.pocketpilot.automation

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Environment
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import app.pocketpilot.capability.accessibility.A11yBridge
import app.pocketpilot.capability.accessibility.A11yOverlay
import app.pocketpilot.capability.shizuku.ShizukuShellRunner
import app.pocketpilot.core.automation.ActionStep
import app.pocketpilot.core.automation.AutomationData
import app.pocketpilot.core.automation.AutomationEngine
import app.pocketpilot.core.automation.ConstraintGroup
import app.pocketpilot.core.automation.ConstraintNode
import app.pocketpilot.core.automation.PickOption
import app.pocketpilot.core.automation.PickSource
import app.pocketpilot.core.automation.Place
import app.pocketpilot.core.automation.Rule
import app.pocketpilot.core.automation.RuleStore
import app.pocketpilot.core.automation.RunOutcome
import app.pocketpilot.core.automation.Schedule
import app.pocketpilot.core.automation.TriggerEvent
import app.pocketpilot.feature.automations.AppOption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import app.pocketpilot.core.designsystem.R as DsR
import app.pocketpilot.feature.automations.R as FeatureR

/**
 * Runs the owner's automations: listens for triggers while any rule is on, sets alarms for timed
 * rules, keeps [AutomationService] up so the process stays alive, and turns it all off again when
 * every rule is off or automations are paused.
 */
class AutomationRuntime(
    private val context: Context,
    val store: RuleStore,
    private val host: AndroidAutomationHost,
    private val notifier: AutomationNotifier,
    private val shell: ShizukuShellRunner,
    private val scope: CoroutineScope,
    private val calendars: Calendars,
    private val homeAssistant: HomeAssistant,
    /** False in the Play build, which never asks for call log or text message access. */
    private val sideload: Boolean,
    sources: (emit: (TriggerEvent) -> Unit) -> TriggerSources,
) {
    val engine = AutomationEngine(store, host, scope)

    private val sources = sources(::onEvent)

    private val mutableApps = MutableStateFlow<List<AppOption>>(emptyList())

    /** Launchable apps for the builder's app picker, loaded the first time it opens. */
    val apps: StateFlow<List<AppOption>> = mutableApps.asStateFlow()

    private val scheduled = ConcurrentHashMap<String, Unit>()
    private val lastFired = ConcurrentHashMap<String, Long>()
    private var active = false
    private var started = false

    /** Called once per process from the application. */
    fun init() {
        if (started) return
        started = true
        scope.launch {
            store.data
                .map(::activeRules)
                .distinctUntilChanged()
                .collect { rules -> withContext(Dispatchers.Main) { apply(rules) } }
        }
        scope.launch {
            combine(store.data.map { floatingButtons(activeRules(it)) }, A11yBridge.connected) { buttons, connected ->
                if (connected) buttons else emptyList()
            }.distinctUntilChanged()
                .collect { buttons -> withContext(Dispatchers.Main) { showFloatingButtons(buttons) } }
        }
        scope.launch {
            var newest =
                store.data.value.runs
                    .firstOrNull()
                    ?.id
            store.data.collect { data ->
                val run = data.runs.firstOrNull() ?: return@collect
                if (run.id == newest) return@collect
                newest = run.id
                if (run.outcome == RunOutcome.FAILED && run.lines.any { LIMIT_MESSAGE in it }) limitReached(data)
            }
        }
    }

    fun onEvent(event: TriggerEvent) = engine.onEvent(event)

    /** The phone entered or left the place with [key], from a proximity alert. */
    fun onPlace(
        key: String,
        entering: Boolean,
    ) {
        // Names the place as the rule that set it calls it.
        val name =
            activeRules(store.data.value)
                .flatMap { it.triggers }
                .filter { it.type == "location.place" }
                .firstOrNull { Place.from(it.params)?.key == key }
                ?.params
                ?.get("name")
                .orEmpty()
        onEvent(TriggerEvent("location.place", mapOf("event" to if (entering) "enter" else "exit", "place" to key, "name" to name)))
    }

    /** The calendar's alarm for the next event start or end. */
    fun onCalendarAlarm() = calendars.onAlarm()

    /** The phone started: rules with "Phone started" run once the triggers are listening. */
    fun onBoot() = onEvent(TriggerEvent("device.boot"))

    /** A timed trigger's alarm went off. */
    fun onAlarm(
        ruleId: String,
        index: Int,
    ) {
        val rule = store.rule(ruleId) ?: return
        val spec = rule.triggers.getOrNull(index) ?: return
        val now = System.currentTimeMillis()
        lastFired[key(ruleId, index)] = now
        onEvent(TriggerEvent(spec.type, mapOf("time" to LocalTime.now().format(HH_MM)), ruleId))
        schedule(rule)
    }

    private val mutableOptions = MutableStateFlow<Map<PickSource, List<PickOption>>>(emptyMap())

    /** Suggested values for text fields, by source; each list loads the first time its field shows. */
    val options: StateFlow<Map<PickSource, List<PickOption>>> = mutableOptions.asStateFlow()

    fun loadOptions(source: PickSource) {
        scope.launch(Dispatchers.IO) {
            val list =
                when (source) {
                    PickSource.VARIABLE -> {
                        store.data.value.variables.keys
                            .sorted()
                            .map { PickOption(it, it) }
                    }

                    PickSource.BLUETOOTH_DEVICE -> {
                        bondedDevices()
                    }

                    PickSource.WIFI_NETWORK -> {
                        knownNetworks()
                    }

                    PickSource.HA_ENTITY -> {
                        homeAssistant.entities()
                    }

                    PickSource.HA_SERVICE -> {
                        homeAssistant.services()
                    }

                    PickSource.CALENDAR -> {
                        calendarNames()
                    }

                    PickSource.INTENT_COMPONENT -> {
                        launcherComponents()
                    }

                    PickSource.INTENT_ACTION -> {
                        INTENT_ACTIONS.map { PickOption(it, it) }
                    }
                }
            mutableOptions.update { it + (source to list) }
        }
    }

    /**
     * The entries of [path] for the file browser: folders first, each with a trailing slash,
     * and a ".." row for the folder above. Reads through All files access, or Shizuku when it is on.
     */
    suspend fun listDirectory(path: String): List<PickOption> =
        withContext(Dispatchers.IO) {
            val folder = path.trimEnd('/').ifBlank { DEFAULT_FOLDER }
            val names: List<String> =
                when {
                    Environment.isExternalStorageManager() -> {
                        File(folder).listFiles().orEmpty().map { if (it.isDirectory) "${it.name}/" else it.name }
                    }

                    shell.available.value -> {
                        val quoted = "'" + folder.replace("'", "'\\''") + "'"
                        runCatching { shell.run("ls -1Ap $quoted", SHELL_TIMEOUT_MS).stdout }
                            .getOrDefault("")
                            .lines()
                            .filter(String::isNotBlank)
                    }

                    else -> {
                        emptyList()
                    }
                }
            val rows =
                names.sortedWith(compareBy({ !it.endsWith("/") }, { it.lowercase() })).map { name ->
                    val isFolder = name.endsWith("/")
                    PickOption("$folder/$name", name.removeSuffix("/"), if (isFolder) "Folder" else null)
                }
            val parent = folder.substringBeforeLast('/', "") + "/"
            listOf(PickOption(parent, "..", "Up one folder")) + rows
        }

    /** Paired devices, by name with their address; needs Bluetooth connect access. */
    @SuppressLint("MissingPermission")
    private fun bondedDevices(): List<PickOption> {
        if (!granted(Manifest.permission.BLUETOOTH_CONNECT)) return emptyList()
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
        return runCatching { adapter.bondedDevices.orEmpty().toList() }
            .getOrDefault(emptyList())
            .map { device ->
                val name = device.name ?: device.address
                PickOption(name, name, device.address)
            }.sortedBy { it.label.lowercase() }
    }

    /** Networks the phone has saved, read through Shizuku, since apps may not read them directly. */
    private suspend fun knownNetworks(): List<PickOption> {
        if (!shell.available.value) return emptyList()
        val reply = runCatching { shell.run("cmd wifi list-networks", SHELL_TIMEOUT_MS) }.getOrNull() ?: return emptyList()
        return SAVED_NETWORK
            .findAll(reply.stdout)
            .map { it.groupValues[1] }
            .distinct()
            .map { PickOption(it, it) }
            .toList()
    }

    private fun calendarNames(): List<PickOption> {
        if (!granted(Manifest.permission.READ_CALENDAR)) return emptyList()
        val cursor =
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME),
                null,
                null,
                null,
            ) ?: return emptyList()
        return cursor.use { rows ->
            generateSequence {
                if (rows.moveToNext()) {
                    rows.getString(
                        0,
                    )
                } else {
                    null
                }
            }.filterNotNull().distinct().map { PickOption(it, it) }.toList()
        }
    }

    /** Apps' launcher screens as "package/class", the form intent components take. */
    private fun launcherComponents(): List<PickOption> {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm
            .queryIntentActivities(launcher, 0)
            .map { info ->
                val component = ComponentName(info.activityInfo.packageName, info.activityInfo.name).flattenToShortString()
                PickOption(component, info.loadLabel(pm).toString(), component)
            }.sortedBy { it.label.lowercase() }
    }

    private fun iconBitmap(drawable: Drawable): Bitmap {
        val px = 48
        val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, px, px)
        drawable.draw(Canvas(bitmap))
        return bitmap
    }

    fun loadApps() {
        if (mutableApps.value.isNotEmpty()) return
        scope.launch(Dispatchers.IO) {
            val pm = context.packageManager
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            mutableApps.value =
                pm
                    .queryIntentActivities(launcher, 0)
                    .map { AppOption(it.activityInfo.packageName, it.loadLabel(pm).toString(), iconBitmap(it.loadIcon(pm))) }
                    .distinctBy { it.packageName }
                    .sortedBy { it.label.lowercase() }
        }
    }

    /** The floating buttons of [rules], one per slot, in slot order. */
    private fun floatingButtons(rules: List<Rule>): List<FloatingSpec> =
        rules
            .flatMap { rule -> rule.triggers.filter { it.type == "floating_button" }.map { FloatingSpec.from(rule.name, it.params) } }
            .distinctBy { it.slot }
            .sortedBy { it.slot }

    /** Puts the buttons over other apps; a tap or long press fires the trigger of its slot and press. */
    private fun showFloatingButtons(buttons: List<FloatingSpec>) {
        A11yOverlay.showButtons(
            buttons.map { spec ->
                A11yOverlay.FloatingButton(
                    text = spec.label,
                    icon = spec.icon(context),
                    small = spec.small,
                    transparent = spec.transparent,
                    opacity = spec.opacity,
                    x = spec.x,
                    y = spec.y,
                    vibrate = spec.vibrate,
                    onTap = { onEvent(TriggerEvent("floating_button", mapOf("slot" to spec.slot, "press" to "tap"))) },
                    onLongPress = { onEvent(TriggerEvent("floating_button", mapOf("slot" to spec.slot, "press" to "long"))) },
                )
            },
        )
    }

    /** The rule for Quick Settings tile [slot], for the tile's label. */
    fun tileRule(slot: Int): Rule? =
        store.rules.firstOrNull { rule ->
            !rule.pending && rule.triggers.any { it.type == "tile" && (it.params["slot"] ?: "1") == slot.toString() }
        }

    private fun activeRules(data: AutomationData): List<Rule> =
        if (data.settings.paused) emptyList() else data.rules.filter { it.enabled && !it.pending }

    private fun apply(rules: List<Rule>) {
        val on = rules.isNotEmpty()
        if (on && !active) {
            active = true
            sources.start()
            AutomationService.start(context)
        } else if (!on && active) {
            active = false
            sources.stop()
            AutomationService.stop(context)
            host.release()
        }
        if (on) {
            AutomationService.update(context, rules.size)
            sources.use(rules)
            grantWhatRulesNeed(rules)
        }
        val ids = rules.map { it.id }.toSet()
        scheduled.keys.filter { it !in ids }.forEach(::cancel)
        rules.forEach(::schedule)
    }

    private fun schedule(rule: Rule) {
        val now = System.currentTimeMillis()
        // An interval counts from when it was first scheduled. Without this, each reschedule restarts the intervals
        // that have not fired yet, so the longest one never comes due while a shorter one keeps firing.
        rule.triggers.forEachIndexed { i, spec -> if (spec.type == "time.interval") lastFired.putIfAbsent(key(rule.id, i), now) }
        val next =
            rule.triggers
                .mapIndexedNotNull { i, spec -> Schedule.next(spec, now, lastFired[key(rule.id, i)])?.let { i to it } }
                .minByOrNull { it.second }
        if (next == null) {
            cancel(rule.id)
            return
        }
        val alarms = context.getSystemService(AlarmManager::class.java)
        val intent = alarmIntent(rule.id, next.first)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.second, intent)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.second, intent)
        }
        scheduled[rule.id] = Unit
    }

    private fun cancel(ruleId: String) {
        scheduled.remove(ruleId)
        val intent =
            PendingIntent.getBroadcast(
                context,
                ruleId.hashCode(),
                Intent(context, AutomationAlarmReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
            ) ?: return
        context.getSystemService(AlarmManager::class.java).cancel(intent)
        intent.cancel()
    }

    private fun alarmIntent(
        ruleId: String,
        index: Int,
    ): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            ruleId.hashCode(),
            Intent(context, AutomationAlarmReceiver::class.java)
                .putExtra(AutomationAlarmReceiver.EXTRA_RULE, ruleId)
                .putExtra(AutomationAlarmReceiver.EXTRA_INDEX, index),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /**
     * Exact alarms for timed rules and Bluetooth device names are permissions Android hides in
     * Settings; with Shizuku they are granted in the background instead.
     */
    private fun grantWhatRulesNeed(rules: List<Rule>) {
        if (!shell.available.value) return
        val types = rules.flatMap(::typesIn).toSet()
        val commands = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarms = context.getSystemService(AlarmManager::class.java)
            if (types.any { it.startsWith("time.") || it == "calendar.event" } && !alarms.canScheduleExactAlarms()) {
                commands += "appops set ${context.packageName} SCHEDULE_EXACT_ALARM allow"
            }
            if (types.any { it.startsWith("bluetooth.") } && !granted(Manifest.permission.BLUETOOTH_CONNECT)) {
                commands += "pm grant ${context.packageName} ${Manifest.permission.BLUETOOTH_CONNECT}"
            }
        }
        val wanted =
            PERMISSIONS
                .filter { (prefix, _) -> types.any { it.startsWith(prefix) } }
                .flatMap { it.second }
        (wanted + types.flatMap { EXACT_PERMISSIONS[it].orEmpty() })
            .filter { sideload || it !in SIDELOAD_PERMISSIONS }
            .distinct()
            .filterNot(::granted)
            .forEach { commands += "pm grant ${context.packageName} $it" }
        if (commands.isEmpty()) return
        scope.launch {
            commands.forEach { runCatching { shell.run(it, SHELL_TIMEOUT_MS) } }
            // Alarms set before the grant were inexact, and sources that needed a permission start now.
            withContext(Dispatchers.Main) {
                rules.forEach(::schedule)
                if (active) sources.use(rules)
            }
        }
    }

    /** Every trigger, condition and action type in [rule], nested ones included. */
    private fun typesIn(rule: Rule): List<String> {
        fun group(g: ConstraintGroup): List<String> =
            g.items.flatMap { node ->
                when (node) {
                    is ConstraintNode.Check -> listOf(node.spec.type)
                    is ConstraintNode.Group -> group(node.group)
                }
            }

        fun steps(list: List<ActionStep>): List<String> =
            list.flatMap { listOf(it.type) + group(it.constraints) + steps(it.children) + steps(it.elseChildren) }
        return rule.triggers.map { it.type } + group(rule.constraints) + steps(rule.actions)
    }

    private fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private var limitNotifiedMonth: String? = null

    private fun limitReached(data: AutomationData) {
        val month = YearMonth.now().toString()
        if (limitNotifiedMonth == month || data.settings.agentMonthlyCapUsd == null) return
        limitNotifiedMonth = month
        notifier.limitReached()
    }

    private fun key(
        ruleId: String,
        index: Int,
    ) = "$ruleId#$index"

    private companion object {
        /** Runtime permissions by trigger or condition type prefix, granted through Shizuku. */
        val PERMISSIONS =
            listOf(
                "location." to
                    listOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                    ),
                "call." to listOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG),
                "sms." to listOf(Manifest.permission.RECEIVE_SMS),
            )

        /** Permissions for single types, so reading a calendar never grants writing to it. */
        val EXACT_PERMISSIONS =
            mapOf(
                "calendar.event" to listOf(Manifest.permission.READ_CALENDAR),
                "calendar.busy" to listOf(Manifest.permission.READ_CALENDAR),
                "calendar.now" to listOf(Manifest.permission.READ_CALENDAR),
                "calendar.read" to listOf(Manifest.permission.READ_CALENDAR),
                "calendar.add" to listOf(Manifest.permission.WRITE_CALENDAR),
                "photo.taken" to listOf(Manifest.permission.READ_MEDIA_IMAGES),
            )
        val SIDELOAD_PERMISSIONS = setOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.RECEIVE_SMS)
        const val SHELL_TIMEOUT_MS = 5_000L

        private val SAVED_NETWORK = Regex("\"([^\"]+)\"")

        private const val DEFAULT_FOLDER = "/storage/emulated/0"

        /** Common Android intent actions, offered for the intent fields. */
        private val INTENT_ACTIONS =
            listOf(
                "android.intent.action.VIEW",
                "android.intent.action.SEND",
                "android.intent.action.SENDTO",
                "android.intent.action.DIAL",
                "android.intent.action.CALL",
                "android.intent.action.EDIT",
                "android.intent.action.INSERT",
                "android.intent.action.WEB_SEARCH",
                "android.media.action.IMAGE_CAPTURE",
                "android.media.action.VIDEO_CAPTURE",
                "android.intent.action.SET_ALARM",
                "android.intent.action.SET_TIMER",
                "android.intent.action.SHOW_ALARMS",
                "android.settings.SETTINGS",
            )
        const val LIMIT_MESSAGE = "monthly AI limit"
        val HH_MM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}

/** The icons a floating button can show, by the choice the rule made. */
private val FLOATING_ICONS =
    mapOf(
        "bolt" to DsR.drawable.core_designsystem_ic_bolt,
        "play" to FeatureR.drawable.feature_automations_ic_play,
        "pin" to FeatureR.drawable.feature_automations_ic_pin,
        "share" to FeatureR.drawable.feature_automations_ic_share,
        "stop" to FeatureR.drawable.feature_automations_ic_stop,
        "check" to FeatureR.drawable.feature_automations_ic_check,
    )

/** How one floating button looks and where it sits, from its trigger's settings. */
private data class FloatingSpec(
    val slot: String,
    val label: String,
    /** A drawable from this app's resources, tinted white. */
    val iconRes: Int?,
    /** The installed app whose icon is shown. */
    val iconPackage: String?,
    val small: Boolean,
    val transparent: Boolean,
    val opacity: Float,
    val x: Int?,
    val y: Int?,
    val vibrate: Boolean,
) {
    /** The icon to draw: a tinted resource, else the app's icon, else none. */
    fun icon(context: Context): Drawable? =
        iconRes?.let { id -> context.getDrawable(id)?.mutate()?.apply { setTint(Color.WHITE) } }
            ?: iconPackage?.let { pkg -> runCatching { context.packageManager.getApplicationIcon(pkg) }.getOrNull() }

    companion object {
        fun from(
            ruleName: String,
            params: Map<String, String>,
        ): FloatingSpec =
            FloatingSpec(
                slot = params["slot"] ?: "1",
                label = params["label"]?.takeIf { it.isNotBlank() } ?: ruleName,
                iconRes = FLOATING_ICONS[params["icon"]],
                iconPackage = if (params["icon"] == "app") params["app"]?.takeIf { it.isNotBlank() } else null,
                small = params["size"] == "small",
                transparent = params["background"] == "transparent",
                opacity = ((params["opacity"]?.toIntOrNull() ?: 100).coerceIn(0, 100)) / 100f,
                x = params["x"]?.toIntOrNull(),
                y = params["y"]?.toIntOrNull(),
                vibrate = params["vibrate"] == "yes",
            )
    }
}
