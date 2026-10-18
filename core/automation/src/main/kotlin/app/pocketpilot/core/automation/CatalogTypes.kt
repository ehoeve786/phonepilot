package app.pocketpilot.core.automation

import app.pocketpilot.core.model.RiskTier
import kotlinx.serialization.json.JsonObject
import java.time.ZonedDateTime

/** How the builder edits a setting. */
enum class FieldKind {
    TEXT,
    MULTILINE,
    NUMBER,
    BOOLEAN,

    /** One of [FieldDef.choices]. */
    CHOICE,

    /** "HH:mm", 24-hour. */
    TIME,

    /** Weekdays as "mon,tue,wed"; empty means every day. */
    DAYS,

    /** Package names, comma separated; empty means any app. */
    APP,

    /** Another rule's id. */
    RULE,

    /** A tool name from the server's registry. */
    TOOL,

    /** A JSON object, such as a tool's arguments. */
    JSON,
}

data class Choice(
    val value: String,
    val label: String,
)

/** One setting of a trigger, constraint or action. */
data class FieldDef(
    val key: String,
    val label: String,
    val kind: FieldKind = FieldKind.TEXT,
    val required: Boolean = true,
    val default: String? = null,
    val choices: List<Choice> = emptyList(),
    val hint: String? = null,
)

/** Groups for the builder's pickers. */
enum class Category(
    val title: String,
) {
    TIME("Time"),
    DEVICE("Device"),
    BATTERY("Battery and power"),
    CONNECTIVITY("Connectivity"),
    APPS("Apps"),
    NOTIFICATIONS("Notifications"),
    SCREEN("Screen and UI"),
    SETTINGS("Settings"),
    SOUND("Sound"),
    MEDIA("Media"),
    LOCATION("Location"),
    CALLS("Calls and messages"),
    SENSORS("Sensors"),
    CALENDAR("Calendar"),
    HOME("Home Assistant"),
    ALARMS("Alarms and timers"),
    FILES("Files and data"),
    FLOW("Flow"),
    VARIABLES("Variables"),
    AI("AI"),
    POCKETPILOT("PocketPilot"),
    MANUAL("Manual and other rules"),
}

/** How PocketPilot learns about a trigger or checks a constraint, shown in the pickers. */
enum class Source(
    val label: String,
) {
    SYSTEM("System"),
    SHIZUKU("Shizuku"),
    ACCESSIBILITY("Accessibility"),
    NOTIFICATIONS("Notification access"),
    POCKETPILOT("PocketPilot"),
    NET("Internet"),
}

/** Something that starts a rule. */
class TriggerDef(
    val type: String,
    val category: Category,
    val title: String,
    val description: String,
    val fields: List<FieldDef> = emptyList(),
    val source: Source = Source.SYSTEM,
    /** Variables the trigger sets, without the `trigger.` prefix. */
    val variables: List<String> = emptyList(),
    /** Fired by its own schedule for one rule, never matched against events. */
    val scheduled: Boolean = false,
    /** Sideload builds only. */
    val sideloadOnly: Boolean = false,
    val sentence: (Map<String, String>) -> String = { title },
    /** True when [event] should start a rule with these settings; [previous] is the same type's last event. */
    val match: (params: Map<String, String>, event: Map<String, String>, previous: Map<String, String>?) -> Boolean =
        { _, _, _ -> true },
)

/** What a constraint can look at. The app answers [state] from the phone. */
interface CheckScope {
    val now: ZonedDateTime

    /** Phone state by key, see [StateKeys]; null when unknown. */
    suspend fun state(key: String): String?

    suspend fun notifications(): List<NotificationInfo>

    /** A run variable, then a global one. */
    fun variable(name: String): String?

    /** The rule being checked. */
    val rule: Rule

    fun lastRunAt(ruleId: String): Long?

    fun runsToday(ruleId: String): Int

    fun ruleEnabled(ruleId: String): Boolean?
}

/** A notification as constraints see it. */
data class NotificationInfo(
    val packageName: String,
    val title: String?,
    val text: String?,
)

/** A check of state. */
class ConstraintDef(
    val type: String,
    val category: Category,
    val title: String,
    val description: String,
    val fields: List<FieldDef> = emptyList(),
    val source: Source = Source.SYSTEM,
    /** Sideload builds only. */
    val sideloadOnly: Boolean = false,
    val sentence: (Map<String, String>) -> String = { title },
    val check: suspend CheckScope.(Map<String, String>) -> Boolean,
)

/** How an action runs. */
sealed interface ActionKind {
    /** One call of [tool] with arguments built from the action's settings. */
    class Tool(
        val tool: String,
        val arguments: (Map<String, String>) -> JsonObject,
    ) : ActionKind

    /** Done by the app itself, such as speaking text or an HTTP request. */
    data object Native : ActionKind

    /** Handled by the engine: flow, variables, several tool calls. */
    data object Engine : ActionKind
}

/** Something a rule does. */
class ActionDef(
    val type: String,
    val category: Category,
    val title: String,
    val description: String,
    val fields: List<FieldDef> = emptyList(),
    val tier: RiskTier,
    val kind: ActionKind,
    /** Every tool the action may call, for the confirmations an owner-made rule skips. */
    val tools: Set<String> = (kind as? ActionKind.Tool)?.let { setOf(it.tool) } ?: emptySet(),
    /** Holds more actions (If, Repeat, While). */
    val container: Boolean = false,
    /** Uses its constraints as its condition (If, While, Wait until). */
    val conditional: Boolean = false,
    /** Sideload builds only. */
    val sideloadOnly: Boolean = false,
    val sentence: (Map<String, String>) -> String = { title },
)

/** Keys of [CheckScope.state] the app answers. */
object StateKeys {
    const val BATTERY_LEVEL = "battery.level"
    const val PLUGGED = "power.plugged"
    const val BATTERY_SAVER = "battery.saver"
    const val SCREEN_ON = "screen.on"
    const val LOCKED = "screen.locked"
    const val WIFI_ENABLED = "wifi.enabled"
    const val WIFI_SSID = "wifi.ssid"
    const val BLUETOOTH_ENABLED = "bluetooth.enabled"

    /** Names of connected Bluetooth devices, one per line. */
    const val BLUETOOTH_DEVICES = "bluetooth.devices"
    const val INTERNET = "internet"
    const val VPN = "vpn"
    const val AIRPLANE = "airplane"
    const val RINGER = "ringer"
    const val DND = "dnd"
    const val HEADSET = "headset"
    const val FOREGROUND = "foreground.package"
    const val DARK_MODE = "dark_mode"
    const val SHIZUKU = "shizuku"
    const val ACCESSIBILITY = "accessibility"
    const val SERVER = "server"
    const val MUSIC = "music.active"

    /** "latitude,longitude" of the phone's last known location. */
    const val LOCATION = "location"

    /** "idle", "ringing" or "in_call". */
    const val CALL = "call.state"

    /** Titles of the calendar events happening now, one per line. */
    const val CALENDAR_NOW = "calendar.now"

    private const val HOME_ASSISTANT = "ha.state:"

    /** The state of Home Assistant entity [entity], such as "on". */
    fun homeAssistant(entity: String) = HOME_ASSISTANT + entity

    /** The entity in a [homeAssistant] key, or null for other keys. */
    fun homeAssistantEntity(key: String): String? = key.takeIf { it.startsWith(HOME_ASSISTANT) }?.removePrefix(HOME_ASSISTANT)

    private const val SCREEN_TEXT = "screen.text:"

    /** Whether [text] is on the screen now, as "on" or "off". */
    fun screenText(text: String) = SCREEN_TEXT + text

    /** The text in a [screenText] key, or null for other keys. */
    fun screenTextOf(key: String): String? = key.takeIf { it.startsWith(SCREEN_TEXT) }?.removePrefix(SCREEN_TEXT)

    private const val SCREEN_PIXEL = "screen.pixel:"

    /** The color of the screen pixel at ([x], [y]) as "#RRGGBB", or null when it cannot be read. */
    fun screenPixel(
        x: Int,
        y: Int,
    ) = "$SCREEN_PIXEL$x,$y"

    /** The position in a [screenPixel] key, or null for other keys. */
    fun screenPixelOf(key: String): Pair<Int, Int>? {
        val parts = key.takeIf { it.startsWith(SCREEN_PIXEL) }?.removePrefix(SCREEN_PIXEL)?.split(',') ?: return null
        val x = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val y = parts.getOrNull(1)?.toIntOrNull() ?: return null
        return x to y
    }
}
