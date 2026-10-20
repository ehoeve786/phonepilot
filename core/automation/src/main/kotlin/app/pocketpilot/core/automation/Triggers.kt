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

    private val SHAKE_LEVELS = listOf("light", "medium", "hard")

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
            stateTrigger("dock.changed", Category.DEVICE, "Dock", "The phone is put in or taken out of a dock.", "Dock"),
            stateTrigger("gps.changed", Category.DEVICE, "GPS", "GPS turned on or off.", "GPS"),
            stateTrigger("nfc.changed", Category.DEVICE, "NFC", "NFC turned on or off.", "NFC"),
            stateTrigger("torch.changed", Category.DEVICE, "Torch", "The flashlight turned on or off.", "Torch"),
            stateTrigger(
                "camera.inuse",
                Category.DEVICE,
                "Camera in use",
                "Another app starts or stops using the camera.",
                "Camera in use",
            ),
            stateTrigger("dark.changed", Category.SCREEN, "Dark theme", "Dark theme turned on or off.", "Dark theme"),
            TriggerDef(
                type = "orientation.changed",
                category = Category.SCREEN,
                title = "Screen orientation",
                description = "The phone turns between portrait and landscape.",
                fields =
                    listOf(
                        choiceField(
                            "to",
                            "To",
                            listOf(Choice("any", "Either"), Choice("portrait", "Portrait"), Choice("landscape", "Landscape")),
                        ),
                    ),
                variables = listOf("orientation"),
                sentence = { p -> if (either(p.p("to"), null)) "Screen turns" else "Screen turns to ${p.p("to")}" },
                match = { p, e, _ -> either(p.p("to"), e["orientation"]) },
            ),
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
            TriggerDef(
                type = "usb.device",
                category = Category.CONNECTIVITY,
                title = "USB device",
                description = "A USB device is plugged in or removed.",
                fields =
                    listOf(
                        choiceField(
                            "state",
                            "When it is",
                            listOf(Choice("any", "Either"), Choice("connected", "Plugged in"), Choice("disconnected", "Removed")),
                        ),
                    ),
                variables = listOf("state", "name"),
                sentence = { p ->
                    when (p.p("state")) {
                        "connected" -> "USB device plugged in"
                        "disconnected" -> "USB device removed"
                        else -> "USB device plugged in or removed"
                    }
                },
                match = { p, e, _ -> either(p.p("state"), e["state"]) },
            ),
            stateTrigger("autorotate.changed", Category.SCREEN, "Auto-rotate", "Screen rotation turned on or off.", "Auto-rotate"),
            TriggerDef(
                type = "accessibility.changed",
                category = Category.SETTINGS,
                title = "Accessibility service",
                description = "An accessibility service is turned on or off. Leave the app blank to match any service.",
                fields =
                    listOf(
                        textField("package", "App package", required = false, hint = "For example com.example.app"),
                        choiceField("state", "When it", STATE),
                    ),
                variables = listOf("package", "state"),
                sentence = { p ->
                    val who = p.p("package").ifBlank { "An accessibility service" }
                    when (p.p("state")) {
                        "on" -> "$who turns on"
                        "off" -> "$who turns off"
                        else -> "$who turns on or off"
                    }
                },
                match = { p, e, _ -> (p.p("package").isEmpty() || p.p("package") == e["package"]) && either(p.p("state"), e["state"]) },
            ),
            TriggerDef(
                type = "ip.changed",
                category = Category.CONNECTIVITY,
                title = "IP address changed",
                description = "The phone's IP address changes, for example after switching networks.",
                variables = listOf("address"),
            ),
            TriggerDef(
                type = "app.enabled",
                category = Category.APPS,
                title = "App turned on or off",
                description = "An app is turned on or off in Settings. Only apps named in a rule are watched.",
                fields =
                    listOf(
                        textField("package", "App package", hint = "For example com.example.app"),
                        choiceField("state", "When it", STATE),
                    ),
                variables = listOf("package", "state"),
                sentence = { p ->
                    when (p.p("state")) {
                        "on" -> "${p.p("package")} turns on"
                        "off" -> "${p.p("package")} turns off"
                        else -> "${p.p("package")} turns on or off"
                    }
                },
                match = { p, e, _ -> p.p("package") == e["package"] && either(p.p("state"), e["state"]) },
            ),
            TriggerDef(
                type = "sensor.reading",
                category = Category.SENSORS,
                title = "Sensor reading",
                description = "A sensor's reading crosses a value you set, such as the light level.",
                fields =
                    listOf(
                        choiceField(
                            "sensor",
                            "Sensor",
                            listOf(
                                Choice("light", "Light"),
                                Choice("pressure", "Air pressure"),
                                Choice("temperature", "Ambient temperature"),
                                Choice("humidity", "Humidity"),
                            ),
                        ),
                        choiceField("direction", "When it goes", listOf(Choice("above", "Above"), Choice("below", "Below"))),
                        numberField("value", "Value"),
                    ),
                variables = listOf("sensor", "direction", "reading"),
                sentence = { p -> "${p.p("sensor")} goes ${p.p("direction")} ${p.p("value")}" },
                match = { p, e, _ ->
                    p.p("sensor") == e["sensor"] && p.p("direction") == e["direction"] && p.p("value") == e["threshold"]
                },
            ),
            TriggerDef(
                type = "shizuku.stopped",
                category = Category.DEVICE,
                title = "Shizuku stopped",
                description = "Shizuku stops running, so shell and settings actions fail until it starts again.",
            ),
            TriggerDef(
                type = "volume.button",
                category = Category.DEVICE,
                title = "Volume button",
                description = "A volume button is pressed. Needs the PocketPilot accessibility service.",
                fields =
                    listOf(
                        choiceField("button", "Button", listOf(Choice("any", "Either"), Choice("up", "Up"), Choice("down", "Down"))),
                    ),
                variables = listOf("button"),
                sentence = { p ->
                    when (p.p("button")) {
                        "up" -> "Volume up pressed"
                        "down" -> "Volume down pressed"
                        else -> "Volume button pressed"
                    }
                },
                match = { p, e, _ -> either(p.p("button"), e["button"]) },
            ),
            TriggerDef(
                type = "media.button",
                category = Category.MEDIA,
                title = "Media button",
                description = "Play or pause, next or previous is pressed on a headset or the phone. Needs the accessibility service.",
                fields =
                    listOf(
                        choiceField(
                            "button",
                            "Button",
                            listOf(
                                Choice("any", "Any"),
                                Choice("play_pause", "Play or pause"),
                                Choice("next", "Next"),
                                Choice("previous", "Previous"),
                            ),
                        ),
                    ),
                variables = listOf("button"),
                sentence = { p -> if (either(p.p("button"), null)) "Media button pressed" else "Media ${p.p("button")} pressed" },
                match = { p, e, _ -> either(p.p("button"), e["button"]) },
            ),
            TriggerDef(
                type = "photo.taken",
                category = Category.DEVICE,
                title = "Photo taken",
                description = "A photo is saved to the camera folder. Needs photo access.",
                variables = listOf("name", "path"),
            ),
            // Battery and power
            TriggerDef(
                type = "battery.temperature",
                category = Category.BATTERY,
                title = "Battery temperature",
                description = "The battery's temperature changes by a whole degree.",
                variables = listOf("celsius"),
            ),
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
            // Media
            TriggerDef(
                type = "media.changed",
                category = Category.MEDIA,
                title = "Music or video",
                description = "Something started or stopped playing, or the track changed. Needs notification access.",
                fields =
                    listOf(
                        choiceField(
                            "state",
                            "When it",
                            listOf(
                                Choice("playing", "Starts playing"),
                                Choice("paused", "Stops or pauses"),
                                Choice("track", "Changes track"),
                                Choice("any", "Any of these"),
                            ),
                        ),
                        appsField(),
                    ),
                source = Source.NOTIFICATIONS,
                variables = listOf("state", "title", "artist", "package"),
                sentence = { p ->
                    val what = if (p.p("apps").isBlank()) "Media" else p.appsText()
                    when (p.p("state")) {
                        "paused" -> "$what stops playing"
                        "track" -> "$what changes track"
                        "any" -> "$what starts, stops or changes track"
                        else -> "$what starts playing"
                    }
                },
                match = { p, e, _ -> either(p.p("state"), e["state"]) && anyOf(p.p("apps"), e["package"]) },
            ),
            // Location
            TriggerDef(
                type = "location.place",
                category = Category.LOCATION,
                title = "Arrive at or leave a place",
                description =
                    "You enter or leave a circle around a spot. Uses the phone's own location, without Google services; " +
                        "needs location access all the time. Copy the spot's coordinates from a map app.",
                fields = PLACE_FIELDS + choiceField("event", "When you", listOf(Choice("enter", "Arrive"), Choice("exit", "Leave"))),
                variables = listOf("event", "name"),
                sentence = { p ->
                    val where = p.p("name").ifBlank { p.p("at") }
                    if (p.p("event") == "exit") "You leave $where" else "You arrive at $where"
                },
                match = { p, e, _ -> either(p.p("event"), e["event"]) && Place.from(p)?.key == e["place"] },
            ),
            // Calls and messages
            TriggerDef(
                type = "call.changed",
                category = Category.CALLS,
                title = "Phone call",
                description =
                    "A call rings, is answered, ends or is missed. The caller's number is known in the sideload build only.",
                fields =
                    listOf(
                        choiceField(
                            "event",
                            "When a call",
                            listOf(
                                Choice("ringing", "Rings"),
                                Choice("answered", "Is answered or dialed"),
                                Choice("ended", "Ends"),
                                Choice("missed", "Is missed"),
                                Choice("any", "Any of these"),
                            ),
                        ),
                        textField("number", "Number contains", required = false, hint = "Sideload build; any caller when empty"),
                    ),
                variables = listOf("event", "number"),
                sentence = { p ->
                    val from = if (p.p("number").isBlank()) "" else " from ${p.p("number")}"
                    when (p.p("event")) {
                        "answered" -> "A call$from is answered"
                        "ended" -> "A call$from ends"
                        "missed" -> "A call$from is missed"
                        "any" -> "A call$from changes"
                        else -> "A call$from rings"
                    }
                },
                match = { p, e, _ -> either(p.p("event"), e["event"]) && digitsContain(p.p("number"), e["number"]) },
            ),
            TriggerDef(
                type = "sms.received",
                category = Category.CALLS,
                title = "Text message received",
                description = "An SMS arrived. Sideload build only.",
                fields =
                    listOf(
                        textField("from", "Sender contains", required = false, hint = "Any sender when empty"),
                        textField("text", "Text contains", required = false),
                    ),
                variables = listOf("from", "text"),
                sideloadOnly = true,
                sentence = { p ->
                    buildString {
                        append("Text message")
                        if (p.p("from").isNotBlank()) append(" from ${p.p("from")}")
                        if (p.p("text").isNotBlank()) append(" saying \"${p.p("text")}\"")
                    }
                },
                match = { p, e, _ -> digitsContain(p.p("from"), e["from"]) && containsText(p.p("text"), e["text"]) },
            ),
            // Sensors
            TriggerDef(
                type = "sensor.shake",
                category = Category.SENSORS,
                title = "Shake the phone",
                description = "A firm shake. Listens to the motion sensor only while a rule uses this.",
                fields =
                    listOf(
                        choiceField(
                            "strength",
                            "Shake",
                            listOf(Choice("medium", "Normal"), Choice("light", "Light"), Choice("hard", "Hard")),
                        ),
                    ),
                sentence = { "You shake the phone" },
                match = { p, e, _ -> SHAKE_LEVELS.indexOf(e["strength"]) >= SHAKE_LEVELS.indexOf(p.p("strength").ifBlank { "medium" }) },
            ),
            TriggerDef(
                type = "sensor.flip",
                category = Category.SENSORS,
                title = "Face down or up",
                description = "You lay the phone face down on a table, or turn it back up.",
                fields =
                    listOf(
                        choiceField(
                            "position",
                            "When it is turned",
                            listOf(Choice("down", "Face down"), Choice("up", "Face up"), Choice("any", "Either")),
                        ),
                    ),
                variables = listOf("position"),
                sentence = { p ->
                    when (p.p("position")) {
                        "up" -> "Phone turned face up"
                        "any" -> "Phone turned over"
                        else -> "Phone laid face down"
                    }
                },
                match = { p, e, _ -> either(p.p("position"), e["position"]) },
            ),
            TriggerDef(
                type = "sensor.proximity",
                category = Category.SENSORS,
                title = "Something near the screen",
                description = "The proximity sensor is covered or uncovered, as by a hand or a pocket.",
                fields =
                    listOf(
                        choiceField(
                            "state",
                            "When it is",
                            listOf(Choice("near", "Covered"), Choice("far", "Uncovered"), Choice("any", "Either")),
                        ),
                    ),
                variables = listOf("state"),
                sentence = { p -> if (p.p("state") == "far") "Proximity sensor uncovered" else "Proximity sensor covered" },
                match = { p, e, _ -> either(p.p("state"), e["state"]) },
            ),
            // Screen content
            TriggerDef(
                type = "screen.text",
                category = Category.SCREEN,
                title = "Text appears on screen",
                description = "Some text shows up on the screen, such as a button or message. Needs the Accessibility service.",
                fields = listOf(textField("text", "Text", hint = "Matched ignoring case"), appsField()),
                source = Source.ACCESSIBILITY,
                variables = listOf("text", "package"),
                sentence = { p -> "\"${p.p("text")}\" appears in ${p.appsText()}" },
                match = { p, e, _ -> containsText(p.p("text"), e["text"]) && anyOf(p.p("apps"), e["package"]) },
            ),
            // Logs
            TriggerDef(
                type = "log.line",
                category = Category.APPS,
                title = "Log line",
                description = "A line in the phone's system log contains some text. Needs Shizuku.",
                fields = listOf(textField("text", "Line contains"), textField("tag", "Tag", required = false, hint = "Any tag when empty")),
                source = Source.SHIZUKU,
                variables = listOf("line", "tag"),
                sentence = { p -> "Log line has \"${p.p("text")}\"" },
                match = { p, e, _ -> containsText(p.p("text"), e["line"]) && anyOf(p.p("tag"), e["tag"]) },
            ),
            TriggerDef(
                type = "app.crash",
                category = Category.APPS,
                title = "App crashed",
                description = "An app crashed or stopped responding. Needs Shizuku.",
                fields = listOf(appsField()),
                source = Source.SHIZUKU,
                variables = listOf("package", "error"),
                sentence = { p -> "${p.appsText()} crashes".replaceFirstChar(Char::uppercase) },
                match = { p, e, _ -> anyOf(p.p("apps"), e["package"]) },
            ),
            // Calendar
            TriggerDef(
                type = "calendar.event",
                category = Category.CALENDAR,
                title = "Calendar event",
                description = "An event in your calendar starts or ends. Needs calendar access.",
                fields =
                    listOf(
                        choiceField("event", "When an event", listOf(Choice("start", "Starts"), Choice("end", "Ends"))),
                        textField("title", "Title contains", required = false, hint = "Any event when empty"),
                        choiceField("all_day", "All-day events", listOf(Choice("no", "Skip"), Choice("yes", "Include"))),
                    ),
                variables = listOf("event", "title", "location", "start", "end"),
                sentence = { p ->
                    val which = if (p.p("title").isBlank()) "An event" else "\"${p.p("title")}\""
                    "$which ${if (p.p("event") == "end") "ends" else "starts"}"
                },
                match = { p, e, _ ->
                    either(p.p("event"), e["event"]) && containsText(p.p("title"), e["title"]) &&
                        (p.p("all_day") == "yes" || e["all_day"] != "yes")
                },
            ),
            // Home Assistant
            TriggerDef(
                type = "ha.state",
                category = Category.HOME,
                title = "Home Assistant entity changes",
                description = "An entity in Home Assistant changes state. Set up Home Assistant under Automations settings.",
                fields =
                    listOf(
                        textField("entity", "Entity ID", hint = "For example binary_sensor.front_door"),
                        textField("to", "Changes to", required = false, hint = "Any change when empty"),
                        textField("from", "Changes from", required = false),
                    ),
                source = Source.NET,
                variables = listOf("entity", "state", "previous"),
                sentence = { p ->
                    buildString {
                        append(p.p("entity"))
                        append(if (p.p("to").isBlank()) " changes" else " turns ${p.p("to")}")
                        if (p.p("from").isNotBlank()) append(" from ${p.p("from")}")
                    }
                },
                match = { p, e, _ ->
                    p.p("entity").equals(e["entity"], ignoreCase = true) &&
                        (p.p("to").isBlank() || p.p("to").equals(e["state"], ignoreCase = true)) &&
                        (p.p("from").isBlank() || p.p("from").equals(e["previous"], ignoreCase = true))
                },
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
                        "with the extra \"event\" set to this name and the extra \"key\" set to the event key in Settings. " +
                        "Other string extras become variables.",
                fields = listOf(textField("event", "Event name", hint = "For example arrived_home")),
                source = Source.POCKETPILOT,
                variables = listOf("event"),
                sentence = { p -> "Event \"${p.p("event")}\" received" },
                match = { p, e, _ -> p.p("event").equals(e["event"], ignoreCase = true) },
            ),
            TriggerDef(
                type = "intent.action",
                category = Category.MANUAL,
                title = "Any broadcast",
                description =
                    "Any app or the system sends a broadcast with this action. String extras become variables " +
                        "named after the extra.",
                fields = listOf(textField("action", "Action", hint = "For example com.example.DONE")),
                source = Source.POCKETPILOT,
                variables = listOf("action", "data"),
                sentence = { p -> "Broadcast ${p.p("action")} received" },
                match = { p, e, _ -> p.p("action") == e["action"] },
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
