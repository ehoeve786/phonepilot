package app.pocketpilot.core.automation

import java.time.LocalTime

/** The constraints this build can check. Most read phone state through [CheckScope.state]. */
internal object Constraints {
    private fun stateIs(
        type: String,
        category: Category,
        title: String,
        key: String,
        noun: String,
    ) = ConstraintDef(
        type = type,
        category = category,
        title = title,
        description = "$noun is on or off.",
        fields = listOf(choiceField("state", "Is", ON_OFF)),
        sentence = { p -> "$noun ${p.p("state").ifBlank { "on" }}" },
        check = { p -> onOff(state(key)) == p.p("state").ifBlank { "on" } },
    )

    val all: List<ConstraintDef> =
        listOf(
            // Time
            ConstraintDef(
                type = "time.window",
                category = Category.TIME,
                title = "Time of day",
                description = "Between two times; a window can run past midnight.",
                fields =
                    listOf(
                        FieldDef("from", "From", FieldKind.TIME, default = "08:00"),
                        FieldDef("to", "To", FieldKind.TIME, default = "18:00"),
                    ),
                sentence = { p -> "${p.p("from")} to ${p.p("to")}" },
                check = { p ->
                    val from = parseTime(p.p("from"))
                    val to = parseTime(p.p("to"))
                    if (from == null || to == null) false else inWindow(now.toLocalTime(), from, to)
                },
            ),
            ConstraintDef(
                type = "time.days",
                category = Category.TIME,
                title = "Days of the week",
                description = "Only on the days you pick.",
                fields = listOf(DAYS_FIELD.copy(required = true, hint = null, default = "mon,tue,wed,thu,fri")),
                sentence = { p -> daysText(p.p("days")).replaceFirstChar(Char::uppercase) },
                check = { p -> now.dayOfWeek in parseDays(p.p("days")) },
            ),
            ConstraintDef(
                type = "screen.text",
                category = Category.SCREEN,
                title = "Text on screen",
                description = "The text is shown on the screen now.",
                fields =
                    listOf(
                        textField("text", "Text"),
                        choiceField("state", "Is", ON_OFF),
                    ),
                sentence = { p -> "\"${p.p("text")}\" ${p.p("state").ifBlank { "on" }} the screen" },
                check = { p -> onOff(state(StateKeys.screenText(p.p("text")))) == p.p("state").ifBlank { "on" } },
            ),
            ConstraintDef(
                type = "screen.tab",
                category = Category.SCREEN,
                title = "Tab selected",
                description = "The tab with this name is selected, shown by the bar under it. Needs no screenshot.",
                fields =
                    listOf(
                        textField("label", "Tab name"),
                        choiceField("state", "Is", ON_OFF),
                    ),
                sentence = { p ->
                    val selected = p.p("state").ifBlank { "on" } == "on"
                    "the \"${p.p("label")}\" tab ${if (selected) "is" else "is not"} selected"
                },
                check = { p ->
                    onOff(state(StateKeys.screenTab(p.p("label")))) == p.p("state").ifBlank { "on" }
                },
            ),
            // Battery and power
            ConstraintDef(
                type = "battery.level",
                category = Category.BATTERY,
                title = "Battery level",
                description = "The battery is within a range.",
                fields =
                    listOf(
                        numberField("min", "At least %", default = "0", required = false),
                        numberField("max", "At most %", default = "100", required = false),
                    ),
                sentence = { p ->
                    val min = p.p("min").ifBlank { "0" }
                    val max = p.p("max").ifBlank { "100" }
                    when {
                        max == "100" -> "Battery at least $min%"
                        min == "0" -> "Battery at most $max%"
                        else -> "Battery $min to $max%"
                    }
                },
                check = { p ->
                    val level = state(StateKeys.BATTERY_LEVEL)?.toIntOrNull()
                    level != null && level >= (p.p("min").toIntOrNull() ?: 0) && level <= (p.p("max").toIntOrNull() ?: 100)
                },
            ),
            ConstraintDef(
                type = "power.charging",
                category = Category.BATTERY,
                title = "Charging",
                description = "The phone is or is not plugged in.",
                fields = listOf(choiceField("state", "Is charging", YES_NO)),
                sentence = { p -> if (p.p("state") == "no") "Not charging" else "Charging" },
                check = { p -> (onOff(state(StateKeys.PLUGGED)) == "on") == (p.p("state") != "no") },
            ),
            stateIs("battery.saver", Category.BATTERY, "Battery saver", StateKeys.BATTERY_SAVER, "Battery saver"),
            // Device
            ConstraintDef(
                type = "screen.state",
                category = Category.DEVICE,
                title = "Screen",
                description = "The screen is on, off, locked or unlocked.",
                fields =
                    listOf(
                        choiceField(
                            "state",
                            "Is",
                            listOf(Choice("on", "On"), Choice("off", "Off"), Choice("unlocked", "Unlocked"), Choice("locked", "Locked")),
                        ),
                    ),
                sentence = { p -> "Screen ${p.p("state").ifBlank { "on" }}" },
                check = { p ->
                    when (p.p("state")) {
                        "off" -> onOff(state(StateKeys.SCREEN_ON)) == "off"
                        "locked" -> onOff(state(StateKeys.LOCKED)) == "on"
                        "unlocked" -> onOff(state(StateKeys.LOCKED)) == "off"
                        else -> onOff(state(StateKeys.SCREEN_ON)) == "on"
                    }
                },
            ),
            stateIs("airplane.on", Category.DEVICE, "Airplane mode", StateKeys.AIRPLANE, "Airplane mode"),
            stateIs("dark_mode.on", Category.DEVICE, "Dark mode", StateKeys.DARK_MODE, "Dark mode"),
            // Sound
            ConstraintDef(
                type = "ringer.mode",
                category = Category.SOUND,
                title = "Ringer mode",
                description = "Sound, vibrate or silent.",
                fields =
                    listOf(
                        choiceField(
                            "mode",
                            "Is",
                            listOf(Choice("normal", "Sound"), Choice("vibrate", "Vibrate"), Choice("silent", "Silent")),
                        ),
                    ),
                sentence = { p -> "Ringer on ${p.p("mode")}" },
                check = { p -> state(StateKeys.RINGER).equals(p.p("mode"), ignoreCase = true) },
            ),
            stateIs("dnd.on", Category.SOUND, "Do Not Disturb", StateKeys.DND, "Do Not Disturb"),
            ConstraintDef(
                type = "headset.connected",
                category = Category.SOUND,
                title = "Headphones connected",
                description = "Wired or Bluetooth headphones are connected.",
                fields = listOf(choiceField("state", "Connected", YES_NO)),
                sentence = { p -> if (p.p("state") == "no") "No headphones" else "Headphones connected" },
                check = { p -> (onOff(state(StateKeys.HEADSET)) == "on") == (p.p("state") != "no") },
            ),
            ConstraintDef(
                type = "music.playing",
                category = Category.MEDIA,
                title = "Music playing",
                description = "Something is playing audio.",
                fields = listOf(choiceField("state", "Playing", YES_NO)),
                sentence = { p -> if (p.p("state") == "no") "Nothing playing" else "Music playing" },
                check = { p -> (onOff(state(StateKeys.MUSIC)) == "on") == (p.p("state") != "no") },
            ),
            // Connectivity
            ConstraintDef(
                type = "wifi.network",
                category = Category.CONNECTIVITY,
                title = "Wi-Fi network",
                description = "Connected, or not connected, to a Wi-Fi network.",
                fields =
                    listOf(
                        choiceField("mode", "Is", listOf(Choice("connected", "Connected to"), Choice("not_connected", "Not connected to"))),
                        pickField(
                            "ssid",
                            "Network name",
                            PickSource.WIFI_NETWORK,
                            required = false,
                            hint = "Any network when empty; several with commas",
                        ),
                    ),
                sentence = { p ->
                    val name = p.p("ssid").ifBlank { "Wi-Fi" }
                    if (p.p("mode") == "not_connected") "Not on $name" else "On $name"
                },
                check = { p ->
                    val ssid = state(StateKeys.WIFI_SSID)?.takeIf { it.isNotBlank() }
                    val on = ssid != null && anyOf(p.p("ssid"), ssid)
                    if (p.p("mode") == "not_connected") !on else on
                },
            ),
            stateIs("wifi.enabled", Category.CONNECTIVITY, "Wi-Fi on", StateKeys.WIFI_ENABLED, "Wi-Fi"),
            stateIs("bluetooth.enabled", Category.CONNECTIVITY, "Bluetooth on", StateKeys.BLUETOOTH_ENABLED, "Bluetooth"),
            ConstraintDef(
                type = "bluetooth.connected",
                category = Category.CONNECTIVITY,
                title = "Bluetooth device connected",
                description = "A Bluetooth device is connected.",
                fields =
                    listOf(
                        pickField("name", "Device name", PickSource.BLUETOOTH_DEVICE, required = false, hint = "Any device when empty"),
                    ),
                sentence = { p -> "${p.p("name").ifBlank { "A Bluetooth device" }} connected" },
                check = { p ->
                    val devices = state(StateKeys.BLUETOOTH_DEVICES).orEmpty().lines().filter(String::isNotBlank)
                    if (p.p("name").isBlank()) devices.isNotEmpty() else devices.any { anyOf(p.p("name"), it) }
                },
            ),
            stateIs("internet.on", Category.CONNECTIVITY, "Internet available", StateKeys.INTERNET, "Internet"),
            stateIs("vpn.on", Category.CONNECTIVITY, "VPN connected", StateKeys.VPN, "VPN"),
            // Apps
            ConstraintDef(
                type = "app.foreground",
                category = Category.APPS,
                title = "App in front",
                description = "One of these apps is, or is not, on screen.",
                fields =
                    listOf(
                        appsField(required = true),
                        choiceField("state", "Is in front", YES_NO),
                    ),
                source = Source.ACCESSIBILITY,
                sentence = { p -> if (p.p("state") == "no") "${p.appsText()} not in front" else "${p.appsText()} in front" },
                check = { p -> anyOf(p.p("apps"), state(StateKeys.FOREGROUND)) == (p.p("state") != "no") },
            ),
            ConstraintDef(
                type = "notification.present",
                category = Category.NOTIFICATIONS,
                title = "Notification showing",
                description = "A notification is, or is not, in the shade.",
                fields =
                    listOf(
                        appsField(),
                        textField("text", "Title or text contains", required = false),
                        choiceField("state", "Is showing", YES_NO),
                    ),
                source = Source.NOTIFICATIONS,
                sentence = { p -> "${if (p.p("state") == "no") "No" else "A"} notification from ${p.appsText()}" },
                check = { p ->
                    val found =
                        notifications().any {
                            anyOf(p.p("apps"), it.packageName) &&
                                (containsText(p.p("text"), it.title) || containsText(p.p("text"), it.text))
                        }
                    found == (p.p("state") != "no")
                },
            ),
            // PocketPilot
            stateIs("shizuku.running", Category.POCKETPILOT, "Shizuku running", StateKeys.SHIZUKU, "Shizuku"),
            stateIs("accessibility.on", Category.POCKETPILOT, "Accessibility on", StateKeys.ACCESSIBILITY, "Accessibility"),
            stateIs("server.running", Category.POCKETPILOT, "Server running", StateKeys.SERVER, "The server"),
            // Variables
            ConstraintDef(
                type = "variable.compare",
                category = Category.VARIABLES,
                title = "Variable",
                description = "Compares a variable with a value.",
                fields =
                    listOf(
                        pickField("name", "Variable", PickSource.VARIABLE, hint = "Without braces, for example trigger.title"),
                        choiceField(
                            "op",
                            "Is",
                            listOf(
                                Choice("equals", "Equal to"),
                                Choice("not_equals", "Not equal to"),
                                Choice("contains", "Contains"),
                                Choice("greater", "Greater than"),
                                Choice("less", "Less than"),
                                Choice("matches", "Matches regex"),
                                Choice("set", "Set"),
                                Choice("not_set", "Not set"),
                            ),
                        ),
                        textField("value", "Value", required = false),
                    ),
                source = Source.POCKETPILOT,
                sentence = { p -> "{${p.p("name")}} ${p.p("op").replace('_', ' ')} ${p.p("value")}".trim() },
                check = { p -> compare(variable(p.p("name")), p.p("op"), p["value"].orEmpty()) },
            ),
            // Rule state
            ConstraintDef(
                type = "rule.cooldown",
                category = Category.MANUAL,
                title = "Not run recently",
                description = "This rule has not run in the last few minutes.",
                fields = listOf(numberField("minutes", "Minutes", default = "30")),
                source = Source.POCKETPILOT,
                sentence = { p -> "Not run in ${p.p("minutes")} min" },
                check = { p ->
                    val last = lastRunAt(rule.id)
                    val minutes = p.p("minutes").toLongOrNull() ?: 0
                    last == null || now.toInstant().toEpochMilli() - last >= minutes * 60_000
                },
            ),
            ConstraintDef(
                type = "rule.runs_today",
                category = Category.MANUAL,
                title = "Runs today",
                description = "This rule ran fewer than so many times today.",
                fields = listOf(numberField("max", "Fewer than", default = "1")),
                source = Source.POCKETPILOT,
                sentence = { p -> "Ran fewer than ${p.p("max")} times today" },
                check = { p -> runsToday(rule.id) < (p.p("max").toIntOrNull() ?: 1) },
            ),
            ConstraintDef(
                type = "rule.enabled",
                category = Category.MANUAL,
                title = "Another rule on",
                description = "Another rule is turned on or off.",
                fields = listOf(FieldDef("rule", "Rule", FieldKind.RULE), choiceField("state", "Is", ON_OFF)),
                source = Source.POCKETPILOT,
                sentence = { p -> "Rule ${p.p("state").ifBlank { "on" }}" },
                check = { p -> ruleEnabled(p.p("rule")) == (p.p("state") != "off") },
            ),
            // Location
            ConstraintDef(
                type = "location.inside",
                category = Category.LOCATION,
                title = "At a place",
                description = "The phone's last known location is inside a circle, or outside it. Needs location access.",
                fields = PLACE_FIELDS + choiceField("state", "Phone is", listOf(Choice("yes", "Inside"), Choice("no", "Outside"))),
                sentence = { p ->
                    val where = p.p("name").ifBlank { p.p("at") }
                    if (p.p("state") == "no") "Not at $where" else "At $where"
                },
                check = { p ->
                    val place = Place.from(p)
                    val here = state(StateKeys.LOCATION)?.let(Place::coordinates)
                    if (place == null || here == null) {
                        false
                    } else {
                        place.contains(here.first, here.second) == (p.p("state") != "no")
                    }
                },
            ),
            // Calls and messages
            ConstraintDef(
                type = "call.state",
                category = Category.CALLS,
                title = "On a call",
                description = "The phone is ringing, on a call, or neither.",
                fields =
                    listOf(
                        choiceField(
                            "state",
                            "Phone is",
                            listOf(Choice("in_call", "On a call"), Choice("ringing", "Ringing"), Choice("idle", "Not on a call")),
                        ),
                    ),
                sentence = { p ->
                    when (p.p("state")) {
                        "ringing" -> "Phone ringing"
                        "idle" -> "Not on a call"
                        else -> "On a call"
                    }
                },
                check = { p -> state(StateKeys.CALL) == p.p("state").ifBlank { "in_call" } },
            ),
            // Calendar
            ConstraintDef(
                type = "calendar.busy",
                category = Category.CALENDAR,
                title = "In a calendar event",
                description = "An event in your calendar is happening now, or none is. Needs calendar access.",
                fields =
                    listOf(
                        textField("title", "Title contains", required = false, hint = "Any event when empty"),
                        choiceField("state", "Is", listOf(Choice("yes", "In an event"), Choice("no", "Free"))),
                    ),
                sentence = { p ->
                    val which = if (p.p("title").isBlank()) "an event" else "\"${p.p("title")}\""
                    if (p.p("state") == "no") "Not in $which" else "In $which"
                },
                check = { p ->
                    val now = state(StateKeys.CALENDAR_NOW).orEmpty().lines().filter(String::isNotBlank)
                    now.any { containsText(p.p("title"), it) } == (p.p("state") != "no")
                },
            ),
            // Home Assistant
            ConstraintDef(
                type = "ha.state",
                category = Category.HOME,
                title = "Home Assistant entity",
                description = "An entity in Home Assistant has a state, such as a door being closed.",
                fields =
                    listOf(
                        pickField("entity", "Entity ID", PickSource.HA_ENTITY, hint = "For example lock.front_door"),
                        choiceField("op", "Is", listOf(Choice("equals", "Equal to"), Choice("not_equals", "Not equal to"))),
                        textField("value", "State", hint = "For example locked"),
                    ),
                source = Source.NET,
                sentence = { p -> "${p.p("entity")} ${if (p.p("op") == "not_equals") "is not" else "is"} ${p.p("value")}" },
                check = { p ->
                    val actual = state(StateKeys.homeAssistant(p.p("entity")))
                    actual != null && (actual.equals(p.p("value"), ignoreCase = true) == (p.p("op") != "not_equals"))
                },
            ),
        )

    /** True when [time] is in [from, to), with windows past midnight wrapping around. */
    fun inWindow(
        time: LocalTime,
        from: LocalTime,
        to: LocalTime,
    ): Boolean =
        if (from <= to) {
            time >= from && time < to
        } else {
            time >= from || time < to
        }

    fun compare(
        actual: String?,
        op: String,
        expected: String,
    ): Boolean =
        when (op) {
            "set" -> !actual.isNullOrEmpty()
            "not_set" -> actual.isNullOrEmpty()
            "not_equals" -> actual != expected
            "contains" -> actual?.contains(expected, ignoreCase = true) == true
            "greater" -> numbers(actual, expected)?.let { (a, b) -> a > b } ?: false
            "less" -> numbers(actual, expected)?.let { (a, b) -> a < b } ?: false
            "matches" -> actual != null && runCatching { SafeRegex.matches(expected, actual) }.getOrDefault(false)
            else -> actual == expected
        }

    private fun numbers(
        a: String?,
        b: String,
    ): Pair<Double, Double>? {
        val x = a?.trim()?.toDoubleOrNull() ?: return null
        val y = b.trim().toDoubleOrNull() ?: return null
        return x to y
    }
}
