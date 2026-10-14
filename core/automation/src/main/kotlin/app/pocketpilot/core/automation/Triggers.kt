package app.pocketpilot.core.automation

/**
 * The triggers this build can fire. The app turns phone events into [TriggerEvent]s with the same
 * type and the data keys listed in [TriggerDef.variables]; rules read them as `{trigger.<key>}`.
 */
internal object Triggers {
    private val STATE = listOf(Choice("any", "Changes"), Choice("on", "Turns on"), Choice("off", "Turns off"))

    private fun stateTrigger(
        type: String,
        category: Category,
        title: String,
        description: String,
        noun: String,
    ) = TriggerDef(
        type = type,
        category = category,
        title = title,
        description = description,
        fields = listOf(choiceField("state", "When it", STATE)),
        variables = listOf("state"),
        sentence = { p ->
            when (p.p("state")) {
                "on" -> "$noun turns on"
                "off" -> "$noun turns off"
                else -> "$noun changes"
            }
        },
        match = { p, e, _ -> either(p.p("state"), e["state"]) },
    )

    val all: List<TriggerDef> =
        listOf(
            // Time
            TriggerDef(
                type = "time.at",
                category = Category.TIME,
                title = "Day and time",
                description = "At a time of day, on the days you pick.",
                fields = listOf(FieldDef("time", "Time", FieldKind.TIME, default = "08:00"), DAYS_FIELD),
                variables = listOf("time"),
                scheduled = true,
                sentence = { p -> "At ${p.p("time")}, ${daysText(p.p("days"))}" },
            ),
            TriggerDef(
                type = "time.interval",
                category = Category.TIME,
                title = "Every few minutes",
                description = "Repeats on an interval. Android may delay short intervals while the phone sleeps.",
                fields = listOf(numberField("minutes", "Every (minutes)", default = "60")),
                scheduled = true,
                sentence = { p -> "Every ${p.p("minutes")} minutes" },
            ),
            TriggerDef(
                type = "time.timezone",
                category = Category.TIME,
                title = "Time zone changed",
                description = "The phone's time zone changed, for example after a flight.",
                variables = listOf("zone"),
            ),
            // Device
            TriggerDef(
                type = "device.boot",
                category = Category.DEVICE,
                title = "Phone started",
                description = "After the phone restarts.",
            ),
            TriggerDef("screen.on", Category.DEVICE, "Screen turned on", "The screen turned on."),
            TriggerDef("screen.off", Category.DEVICE, "Screen turned off", "The screen turned off."),
            TriggerDef("screen.unlocked", Category.DEVICE, "Phone unlocked", "You unlocked the phone."),
            stateTrigger("airplane.changed", Category.DEVICE, "Airplane mode", "Airplane mode turned on or off.", "Airplane mode"),
            stateTrigger("dnd.changed", Category.SOUND, "Do Not Disturb", "Do Not Disturb turned on or off.", "Do Not Disturb"),
            TriggerDef(
                type = "ringer.changed",
                category = Category.SOUND,
                title = "Ringer mode changed",
                description = "Sound, vibrate or silent.",
                fields =
                    listOf(
                        choiceField(
                            "mode",
                            "To",
                            listOf(
                                Choice("any", "Any"),
                                Choice("normal", "Sound"),
                                Choice("vibrate", "Vibrate"),
                                Choice("silent", "Silent"),
                            ),
                        ),
                    ),
                variables = listOf("mode"),
                sentence = { p -> if (either(p.p("mode"), null)) "Ringer mode changes" else "Ringer set to ${p.p("mode")}" },
                match = { p, e, _ -> either(p.p("mode"), e["mode"]) },
            ),
            TriggerDef(
                type = "headset.changed",
                category = Category.SOUND,
                title = "Headphones",
                description = "Wired or Bluetooth headphones connected or removed.",
                fields =
                    listOf(
                        choiceField(
                            "state",
                            "When they are",
                            listOf(Choice("any", "Either"), Choice("connected", "Connected"), Choice("disconnected", "Removed")),
                        ),
                    ),
                variables = listOf("state", "name"),
                sentence = { p ->
                    when (p.p("state")) {
                        "connected" -> "Headphones connected"
                        "disconnected" -> "Headphones removed"
                        else -> "Headphones connected or removed"
                    }
                },
                match = { p, e, _ -> either(p.p("state"), e["state"]) },
            ),
            // Battery and power
            TriggerDef(
                type = "power.connected",
                category = Category.BATTERY,
                title = "Charger connected",
                description = "Plugged in, optionally only for one kind of charger.",
                fields =
                    listOf(
                        choiceField(
                            "source",
                            "Charger",
                            listOf(Choice("any", "Any"), Choice("ac", "Wall"), Choice("usb", "USB"), Choice("wireless", "Wireless")),
                        ),
                    ),
                variables = listOf("source", "level"),
                sentence = { p -> if (either(p.p("source"), null)) "Charger connected" else "${p.p("source")} charger connected" },
                match = { p, e, _ -> either(p.p("source"), e["source"]) },
            ),
            TriggerDef(
                type = "power.disconnected",
                category = Category.BATTERY,
                title = "Charger disconnected",
                description = "Unplugged.",
                variables = listOf("level"),
            ),
            TriggerDef(
                type = "battery.level",
                category = Category.BATTERY,
                title = "Battery level",
                description = "The battery drops below or rises above a level.",
                fields =
                    listOf(
                        choiceField("direction", "When it", listOf(Choice("below", "Drops below"), Choice("above", "Rises above"))),
                        numberField("percent", "Percent", default = "20"),
                    ),
                variables = listOf("level"),
                sentence = { p -> "Battery ${if (p.p("direction") == "above") "rises above" else "drops below"} ${p.p("percent")}%" },
                match = { p, e, previous ->
                    val limit = p.p("percent").toIntOrNull()
                    val now = e["level"]?.toIntOrNull()
                    val before = previous?.get("level")?.toIntOrNull()
                    when {
                        limit == null || now == null -> false
                        p.p("direction") == "above" -> now > limit && (before == null || before <= limit)
                        else -> now < limit && (before == null || before >= limit)
                    }
                },
            ),
            stateTrigger("battery.saver", Category.BATTERY, "Battery saver", "Battery saver turned on or off.", "Battery saver"),
            // Connectivity
            TriggerDef(
                type = "wifi.connected",
                category = Category.CONNECTIVITY,
                title = "Wi-Fi connected",
                description = "Connected to a Wi-Fi network. Reading the network name needs location access.",
                fields = listOf(textField("ssid", "Network name", required = false, hint = "Any network when empty; several with commas")),
                variables = listOf("ssid"),
                sentence = { p -> if (p.p("ssid").isBlank()) "Wi-Fi connects" else "Connected to ${p.p("ssid")}" },
                match = { p, e, _ -> anyOf(p.p("ssid"), e["ssid"]) },
            ),
            TriggerDef(
                type = "wifi.disconnected",
                category = Category.CONNECTIVITY,
                title = "Wi-Fi disconnected",
                description = "Left a Wi-Fi network.",
                fields = listOf(textField("ssid", "Network name", required = false, hint = "Any network when empty")),
                variables = listOf("ssid"),
                sentence = { p -> if (p.p("ssid").isBlank()) "Wi-Fi disconnects" else "Left ${p.p("ssid")}" },
                match = { p, e, _ -> anyOf(p.p("ssid"), e["ssid"]) },
            ),
            stateTrigger("wifi.state", Category.CONNECTIVITY, "Wi-Fi on or off", "Wi-Fi turned on or off.", "Wi-Fi"),
            stateTrigger("bluetooth.state", Category.CONNECTIVITY, "Bluetooth on or off", "Bluetooth turned on or off.", "Bluetooth"),
            TriggerDef(
                type = "bluetooth.device",
                category = Category.CONNECTIVITY,
                title = "Bluetooth device",
                description = "A Bluetooth device connected or disconnected, such as your car or earbuds.",
                fields =
                    listOf(
                        choiceField(
                            "event",
                            "When it",
                            listOf(Choice("connected", "Connects"), Choice("disconnected", "Disconnects"), Choice("any", "Either")),
                        ),
                        textField("name", "Device name", required = false, hint = "Any device when empty; several with commas"),
                    ),
                variables = listOf("event", "name", "address"),
                sentence = { p ->
                    val who = p.p("name").ifBlank { "A Bluetooth device" }
                    when (p.p("event")) {
                        "disconnected" -> "$who disconnects"
                        "any" -> "$who connects or disconnects"
                        else -> "$who connects"
                    }
                },
                match = { p, e, _ -> either(p.p("event"), e["event"]) && anyOf(p.p("name"), e["name"]) },
            ),
            TriggerDef(
                type = "internet.changed",
                category = Category.CONNECTIVITY,
                title = "Internet connection",
                description = "The phone gained or lost a working internet connection.",
                fields =
                    listOf(
                        choiceField(
                            "state",
                            "When it is",
                            listOf(Choice("any", "Either"), Choice("on", "Available"), Choice("off", "Lost")),
                        ),
                    ),
                variables = listOf("state"),
                sentence = { p ->
                    when (p.p("state")) {
                        "on" -> "Internet comes back"
                        "off" -> "Internet is lost"
                        else -> "Internet connection changes"
                    }
                },
                match = { p, e, _ -> either(p.p("state"), e["state"]) },
            ),
            stateTrigger("vpn.changed", Category.CONNECTIVITY, "VPN", "A VPN connected or disconnected.", "VPN"),
            // Apps
            TriggerDef(
                type = "app.opened",
                category = Category.APPS,
                title = "App opened",
                description = "An app came to the front. Needs the Accessibility service.",
                fields = listOf(appsField()),
                source = Source.ACCESSIBILITY,
                variables = listOf("package"),
                sentence = { p -> "${p.appsText()} opens".replaceFirstChar(Char::uppercase) },
                match = { p, e, _ -> anyOf(p.p("apps"), e["package"]) },
            ),
            TriggerDef(
                type = "app.closed",
                category = Category.APPS,
                title = "App closed",
                description = "You left an app. Needs the Accessibility service.",
                fields = listOf(appsField()),
                source = Source.ACCESSIBILITY,
                variables = listOf("package"),
                sentence = { p -> "${p.appsText()} closes".replaceFirstChar(Char::uppercase) },
                match = { p, e, _ -> anyOf(p.p("apps"), e["package"]) },
            ),
            TriggerDef(
                type = "app.package",
                category = Category.APPS,
                title = "App installed, updated or removed",
                description = "An app was installed, updated or uninstalled.",
                fields =
                    listOf(
                        choiceField(
                            "event",
                            "When it is",
                            listOf(
                                Choice("any", "Any change"),
                                Choice("installed", "Installed"),
                                Choice("updated", "Updated"),
                                Choice("removed", "Removed"),
                            ),
                        ),
                        appsField(),
                    ),
                variables = listOf("event", "package"),
                sentence = { p ->
                    "${p.appsText()} is ${if (either(p.p("event"), null)) "changed" else p.p("event")}".replaceFirstChar(Char::uppercase)
                },
                match = { p, e, _ -> either(p.p("event"), e["event"]) && anyOf(p.p("apps"), e["package"]) },
            ),
            // Notifications
            TriggerDef(
                type = "notification.posted",
                category = Category.NOTIFICATIONS,
                title = "Notification received",
                description = "A notification arrived. Banking and other protected apps are never read.",
                fields =
                    listOf(
                        appsField(),
                        textField("title", "Title contains", required = false),
                        textField("text", "Text contains", required = false),
                    ),
                source = Source.NOTIFICATIONS,
                variables = listOf("package", "title", "text", "key"),
                sentence = { p ->
                    buildString {
                        append("Notification from ${p.appsText()}")
                        if (p.p("title").isNotBlank()) append(", title has \"${p.p("title")}\"")
                        if (p.p("text").isNotBlank()) append(", text has \"${p.p("text")}\"")
                    }
                },
                match = { p, e, _ ->
                    anyOf(p.p("apps"), e["package"]) && containsText(p.p("title"), e["title"]) && containsText(p.p("text"), e["text"])
                },
            ),
            TriggerDef(
                type = "notification.removed",
                category = Category.NOTIFICATIONS,
                title = "Notification cleared",
                description = "A notification went away.",
                fields = listOf(appsField(), textField("title", "Title contains", required = false)),
                source = Source.NOTIFICATIONS,
                variables = listOf("package", "title", "text"),
                sentence = { p -> "Notification from ${p.appsText()} cleared" },
                match = { p, e, _ -> anyOf(p.p("apps"), e["package"]) && containsText(p.p("title"), e["title"]) },
            ),
            // Manual and other rules
            TriggerDef(
                type = "manual",
                category = Category.MANUAL,
                title = "Run button only",
                description = "Runs only when you press Run, or from another rule.",
                source = Source.POCKETPILOT,
            ),
            TriggerDef(
                type = "tile",
                category = Category.MANUAL,
                title = "Quick Settings tile",
                description = "Add a PocketPilot tile to Quick Settings and tap it to run the rule.",
                fields = listOf(choiceField("slot", "Tile", listOf(Choice("1", "Tile 1"), Choice("2", "Tile 2"), Choice("3", "Tile 3")))),
                source = Source.POCKETPILOT,
                sentence = { p -> "Quick Settings tile ${p.p("slot")} tapped" },
                match = { p, e, _ -> p.p("slot").ifBlank { "1" } == e["slot"] },
            ),
            TriggerDef(
                type = "intent.received",
                category = Category.MANUAL,
                title = "Intent from another app",
                description =
                    "Another app (Tasker, MacroDroid, Termux) sends the broadcast app.pocketpilot.action.EVENT " +
                        "with the extra \"event\" set to this name; other string extras become variables.",
                fields = listOf(textField("event", "Event name", hint = "For example arrived_home")),
                source = Source.POCKETPILOT,
                variables = listOf("event"),
                sentence = { p -> "Event \"${p.p("event")}\" received" },
                match = { p, e, _ -> p.p("event").equals(e["event"], ignoreCase = true) },
            ),
            TriggerDef(
                type = "mcp.call",
                category = Category.MANUAL,
                title = "AI app asks",
                description = "An AI app connected to PocketPilot runs this rule with automation.run.",
                source = Source.POCKETPILOT,
                variables = listOf("client"),
                sentence = { "An AI app runs it" },
            ),
            TriggerDef(
                type = "rule.finished",
                category = Category.MANUAL,
                title = "Another rule finished",
                description = "Runs after another rule.",
                fields =
                    listOf(
                        FieldDef("rule", "Rule", FieldKind.RULE),
                        choiceField(
                            "outcome",
                            "When it",
                            listOf(Choice("any", "Finishes"), Choice("ok", "Succeeds"), Choice("failed", "Fails")),
                        ),
                    ),
                source = Source.POCKETPILOT,
                variables = listOf("rule", "outcome"),
                sentence = { "Another rule finishes" },
                match = { p, e, _ -> p.p("rule") == e["ruleId"] && either(p.p("outcome"), e["outcome"]) },
            ),
            TriggerDef(
                type = "variable.changed",
                category = Category.VARIABLES,
                title = "Variable changed",
                description = "A global variable got a new value.",
                fields = listOf(textField("name", "Variable"), textField("value", "New value is", required = false)),
                source = Source.POCKETPILOT,
                variables = listOf("name", "value"),
                sentence = { p -> "{${p.p("name")}} changes" },
                match = { p, e, _ -> p.p("name") == e["name"] && (p.p("value").isBlank() || p.p("value") == e["value"]) },
            ),
        )
}
