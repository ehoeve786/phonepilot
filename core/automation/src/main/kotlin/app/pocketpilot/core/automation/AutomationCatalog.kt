package app.pocketpilot.core.automation

import app.pocketpilot.capability.api.settings.SettingKey
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.DayOfWeek
import java.time.LocalTime

/**
 * Every trigger, constraint and action this build offers. The builder, `automation.catalog` and the
 * engine all read it, so adding an entry here is all a new trigger or action needs on the engine side.
 */
object AutomationCatalog {
    val triggers: List<TriggerDef> by lazy { Triggers.all }
    val constraints: List<ConstraintDef> by lazy { Constraints.all }
    val actions: List<ActionDef> by lazy { Actions.all }

    private val triggersByType by lazy { triggers.associateBy { it.type } }
    private val constraintsByType by lazy { constraints.associateBy { it.type } }
    private val actionsByType by lazy { actions.associateBy { it.type } }

    fun trigger(type: String): TriggerDef? = triggersByType[type]

    fun constraint(type: String): ConstraintDef? = constraintsByType[type]

    fun action(type: String): ActionDef? = actionsByType[type]
}

// Shared fields and checks.

internal val ON_OFF = listOf(Choice("on", "On"), Choice("off", "Off"))
internal val ON_OFF_ANY = listOf(Choice("any", "Either"), Choice("on", "On"), Choice("off", "Off"))
internal val YES_NO = listOf(Choice("yes", "Yes"), Choice("no", "No"))

internal fun appsField(
    label: String = "Apps",
    required: Boolean = false,
) = FieldDef("apps", label, FieldKind.APP, required = required, hint = if (required) null else "Any app when empty")

internal fun singleAppField(
    key: String,
    label: String,
    required: Boolean = true,
    hint: String? = null,
) = FieldDef(key, label, FieldKind.APP, required = required, hint = hint, single = true)

internal fun pickField(
    key: String,
    label: String,
    source: PickSource,
    required: Boolean = true,
    hint: String? = null,
) = FieldDef(key, label, FieldKind.TEXT, required = required, hint = hint, source = source)

internal fun fileField(
    key: String,
    label: String,
    hint: String? = null,
) = FieldDef(key, label, FieldKind.TEXT, hint = hint, browse = true)

internal fun textField(
    key: String,
    label: String,
    required: Boolean = true,
    hint: String? = null,
    default: String? = null,
) = FieldDef(key, label, FieldKind.TEXT, required = required, hint = hint, default = default)

internal fun choiceField(
    key: String,
    label: String,
    choices: List<Choice>,
    default: String = choices.first().value,
) = FieldDef(key, label, FieldKind.CHOICE, default = default, choices = choices)

internal fun iconField(
    key: String,
    label: String,
    choices: List<Choice>,
    default: String = choices.first().value,
) = FieldDef(key, label, FieldKind.ICON, default = default, choices = choices)

internal fun numberField(
    key: String,
    label: String,
    default: String? = null,
    hint: String? = null,
    required: Boolean = true,
) = FieldDef(key, label, FieldKind.NUMBER, required = required, default = default, hint = hint)

/** Blank [param] matches anything; otherwise any of its comma-separated values equals [value]. */
internal fun anyOf(
    param: String?,
    value: String?,
): Boolean = param.isNullOrBlank() || param.split(',').map(String::trim).any { it.equals(value, ignoreCase = true) }

/** Blank [param] matches anything; otherwise [value] contains it, ignoring case. */
internal fun containsText(
    param: String?,
    value: String?,
): Boolean = param.isNullOrBlank() || value?.contains(param.trim(), ignoreCase = true) == true

/** "any" or blank matches anything. */
internal fun either(
    param: String?,
    value: String?,
): Boolean = param.isNullOrBlank() || param == "any" || param.equals(value, ignoreCase = true)

/** Blank [param] matches anything; otherwise [value] contains it, comparing only digits when [param] is a phone number. */
internal fun digitsContain(
    param: String?,
    value: String?,
): Boolean {
    if (param.isNullOrBlank()) return true
    val digits = param.filter(Char::isDigit)
    if (digits.isEmpty() || digits.length < param.count { !it.isWhitespace() && it !in "+-()." }) return containsText(param, value)
    return value?.filter(Char::isDigit)?.contains(digits) == true
}

/** Where a location trigger or condition is: a spot and a radius. */
internal val PLACE_FIELDS =
    listOf(
        textField("name", "Name", required = false, hint = "For example Home"),
        textField("at", "Latitude, longitude", hint = "For example 49.2827, -123.1207"),
        numberField("radius", "Radius (meters)", default = "150", hint = "At least 50; 150 or more is more reliable"),
    )

internal fun Map<String, String>.p(key: String): String = this[key].orEmpty().trim()

internal fun Map<String, String>.appsText(): String = p("apps").ifBlank { "any app" }

internal fun onOff(value: String?): String? =
    when (value?.lowercase()) {
        "true", "on", "yes", "1" -> "on"
        "false", "off", "no", "0" -> "off"
        else -> null
    }

internal fun parseDays(text: String): Set<DayOfWeek> =
    text
        .split(',')
        .map { it.trim().lowercase() }
        .filter(String::isNotEmpty)
        .mapNotNull { day -> DayOfWeek.entries.firstOrNull { it.name.lowercase().startsWith(day.take(3)) } }
        .toSet()
        .ifEmpty { DayOfWeek.entries.toSet() }

internal fun daysText(text: String): String {
    val days = parseDays(text)
    return when (days) {
        DayOfWeek.entries.toSet() -> {
            "every day"
        }

        setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY) -> {
            "weekdays"
        }

        setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) -> {
            "weekends"
        }

        else -> {
            days.sorted().joinToString {
                it.name
                    .take(3)
                    .lowercase()
                    .replaceFirstChar(Char::uppercase)
            }
        }
    }
}

internal fun parseTime(text: String): LocalTime? = runCatching { LocalTime.parse(text.trim().padStart(5, '0')) }.getOrNull()

internal val DAYS_FIELD = FieldDef("days", "Days", FieldKind.DAYS, required = false, hint = "Every day when empty")

/** Settings a rule can change through `settings.set`, with plain names. */
internal val SETTING_TITLES =
    mapOf(
        SettingKey.DARK_MODE to "Dark mode",
        SettingKey.WIFI to "Wi-Fi",
        SettingKey.BLUETOOTH to "Bluetooth",
        SettingKey.DND to "Do Not Disturb",
        SettingKey.BRIGHTNESS to "Brightness",
        SettingKey.ROTATION to "Auto rotate",
        SettingKey.VOLUME to "Media volume",
        SettingKey.RING_VOLUME to "Ring volume",
        SettingKey.ALARM_VOLUME to "Alarm volume",
        SettingKey.NOTIFICATION_VOLUME to "Notification volume",
        SettingKey.AIRPLANE_MODE to "Airplane mode",
        SettingKey.MOBILE_DATA to "Mobile data",
        SettingKey.LOCATION to "Location",
        SettingKey.BATTERY_SAVER to "Battery saver",
        SettingKey.STAY_AWAKE to "Stay awake while charging",
        SettingKey.SCREEN_TIMEOUT to "Screen timeout",
        SettingKey.FONT_SCALE to "Font size",
        SettingKey.ANIMATIONS to "Animations",
        SettingKey.AMBIENT_DISPLAY to "Ambient display",
    )

internal fun args(vararg pairs: Pair<String, Any?>): JsonObject =
    buildJsonObject {
        pairs.forEach { (key, value) ->
            when (value) {
                null -> Unit
                is String -> if (value.isNotEmpty()) put(key, value)
                is Boolean -> put(key, value)
                is Number -> put(key, value)
                is JsonObject -> put(key, value)
                else -> put(key, JsonPrimitive(value.toString()))
            }
        }
    }
