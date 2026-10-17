package app.pocketpilot.core.automation

import app.pocketpilot.capability.api.settings.SettingKey
import app.pocketpilot.core.model.RiskTier
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * The actions this build can run. Tool actions are one PocketPilot tool call, so they go through the
 * same policy, deny list and audit as calls from AI apps; engine actions are flow, variables and
 * steps that combine several calls; native actions are done by the app itself.
 */
internal object Actions {
    /** Actions that store a result put it in this variable. */
    private val INTO = textField("into", "Save result in variable", required = false, hint = "For example result")

    private fun settingAction(key: SettingKey): ActionDef {
        val title = SETTING_TITLES[key] ?: key.wire
        val field =
            when (key) {
                SettingKey.DARK_MODE -> {
                    choiceField("value", "Set to", ON_OFF + Choice("auto", "Automatic") + Choice("toggle", "Toggle"))
                }

                SettingKey.ROTATION -> {
                    choiceField("value", "Set to", listOf(Choice("auto", "Auto rotate"), Choice("locked", "Locked")))
                }

                SettingKey.BRIGHTNESS -> {
                    textField("value", "Percent, or auto", default = "50")
                }

                SettingKey.VOLUME, SettingKey.RING_VOLUME, SettingKey.ALARM_VOLUME, SettingKey.NOTIFICATION_VOLUME -> {
                    numberField("value", "Percent", default = "50")
                }

                SettingKey.SCREEN_TIMEOUT -> {
                    numberField("value", "Seconds (15 to 1800)", default = "60")
                }

                SettingKey.FONT_SCALE -> {
                    textField("value", "Scale (0.8 to 2.0)", default = "1.0")
                }

                else -> {
                    choiceField("value", "Turn", ON_OFF + Choice("toggle", "Toggle"))
                }
            }
        return ActionDef(
            type = "setting.${key.wire}",
            category = Category.SETTINGS,
            title = title,
            description = "Changes $title in the background through Shizuku.",
            fields = listOf(field),
            tier = RiskTier.SENSITIVE,
            kind = ActionKind.Engine,
            tools = setOf("settings.set", "settings.get"),
            sentence = { p ->
                when (val v = p.p("value")) {
                    "toggle" -> "Toggle $title"
                    "on", "off" -> "Turn $title $v"
                    else -> "Set $title to $v"
                }
            },
        )
    }

    private fun intentExtras(p: Map<String, String>): JsonObject? =
        p.p("extras").takeIf { it.isNotBlank() }?.let { text ->
            runCatching { AutomationJson.json.parseToJsonElement(text).jsonObject }.getOrNull()
                ?: throw AutomationFailure("Extras must be a JSON object, for example {\"name\": \"value\"}")
        }

    private val ui = Category.SCREEN

    val all: List<ActionDef> =
        SettingKey.entries.map(::settingAction) +
            listOf(
                // Apps
                ActionDef(
                    type = "app.launch",
                    category = Category.APPS,
                    title = "Open an app",
                    description = "Brings an app to the front.",
                    fields = listOf(appsField("App", required = true)),
                    tier = RiskTier.INTERACT,
                    kind =
                        ActionKind.Tool("app.launch") { p ->
                            args(
                                "package" to p.p("apps").substringBefore(',').trim(),
                                "returnSnapshot" to false,
                            )
                        },
                    sentence = { p -> "Open ${p.p("apps")}" },
                ),
                ActionDef(
                    type = "app.start_activity",
                    category = Category.APPS,
                    title = "Open a screen in an app",
                    description = "Starts an activity or intent directly, such as Clock's stopwatch, without tapping through the app.",
                    fields =
                        listOf(
                            textField(
                                "component",
                                "Component",
                                required = false,
                                hint = "package/class, for example com.google.android.deskclock/.DeskClock",
                            ),
                            textField("package", "Package", required = false, hint = "For an implicit intent"),
                            textField("action", "Action", required = false, hint = "For example android.intent.action.VIEW"),
                            textField("data", "Data URI", required = false),
                            FieldDef("extras", "Extras (JSON)", FieldKind.JSON, required = false),
                        ),
                    tier = RiskTier.INTERACT,
                    kind =
                        ActionKind.Tool("app.start_activity") { p ->
                            args(
                                "component" to p.p("component"),
                                "package" to p.p("package"),
                                "action" to p.p("action"),
                                "data" to p.p("data"),
                                "extras" to intentExtras(p),
                                "returnSnapshot" to false,
                            )
                        },
                    sentence = { p -> "Open ${p.p("component").ifBlank { p.p("action").ifBlank { p.p("package") } }}" },
                ),
                ActionDef(
                    type = "app.send_broadcast",
                    category = Category.APPS,
                    title = "Send a broadcast",
                    description = "Sends a broadcast intent, for example to Tasker, MacroDroid or another app.",
                    fields =
                        listOf(
                            textField("action", "Action"),
                            textField("package", "Package", required = false),
                            textField("component", "Component", required = false),
                            FieldDef("extras", "Extras (JSON)", FieldKind.JSON, required = false),
                        ),
                    tier = RiskTier.SENSITIVE,
                    kind =
                        ActionKind.Tool("app.send_broadcast") { p ->
                            args(
                                "action" to p.p("action"),
                                "package" to p.p("package"),
                                "component" to p.p("component"),
                                "extras" to intentExtras(p),
                            )
                        },
                    sentence = { p -> "Broadcast ${p.p("action")}" },
                ),
                ActionDef(
                    type = "app.open_url",
                    category = Category.APPS,
                    title = "Open a link",
                    description = "Opens a web link in the app that handles it.",
                    fields = listOf(textField("url", "Link", hint = "https://…")),
                    tier = RiskTier.INTERACT,
                    kind = ActionKind.Tool("app.open_url") { p -> args("url" to p.p("url"), "returnSnapshot" to false) },
                    sentence = { p -> "Open ${p.p("url")}" },
                ),
                ActionDef(
                    type = "app.stop",
                    category = Category.APPS,
                    title = "Force stop an app",
                    description = "Force-stops an app and its background work.",
                    fields = listOf(appsField("App", required = true)),
                    tier = RiskTier.SENSITIVE,
                    kind = ActionKind.Tool("app.stop") { p -> args("package" to p.p("apps").substringBefore(',').trim()) },
                    sentence = { p -> "Force stop ${p.p("apps")}" },
                ),
                ActionDef(
                    type = "app.set_enabled",
                    category = Category.APPS,
                    title = "Freeze or unfreeze an app",
                    description = "Disables an app so it cannot run, or enables it again.",
                    fields =
                        listOf(
                            appsField("App", required = true),
                            choiceField("enabled", "Set to", listOf(Choice("false", "Frozen"), Choice("true", "Enabled"))),
                        ),
                    tier = RiskTier.DESTRUCTIVE,
                    kind =
                        ActionKind.Tool("app.set_enabled") { p ->
                            args("package" to p.p("apps").substringBefore(',').trim(), "enabled" to (p.p("enabled") == "true"))
                        },
                    sentence = { p -> "${if (p.p("enabled") == "true") "Enable" else "Freeze"} ${p.p("apps")}" },
                ),
                ActionDef(
                    type = "app.clear_data",
                    category = Category.APPS,
                    title = "Clear an app's data",
                    description = "Deletes all of an app's data, like Clear storage.",
                    fields = listOf(appsField("App", required = true)),
                    tier = RiskTier.DESTRUCTIVE,
                    kind = ActionKind.Tool("app.clear_data") { p -> args("package" to p.p("apps").substringBefore(',').trim()) },
                    sentence = { p -> "Clear data of ${p.p("apps")}" },
                ),
                // Alarms and timers
                ActionDef(
                    type = "timer.set",
                    category = Category.ALARMS,
                    title = "Set a timer",
                    description = "Starts a timer in the clock app without opening it.",
                    fields =
                        listOf(
                            numberField("minutes", "Minutes", default = "5"),
                            numberField("seconds", "Seconds", default = "0", required = false),
                            textField("label", "Label", required = false),
                        ),
                    tier = RiskTier.INTERACT,
                    kind =
                        ActionKind.Tool("app.start_activity") { p ->
                            val seconds = (p.p("minutes").toIntOrNull() ?: 0) * 60 + (p.p("seconds").toIntOrNull() ?: 0)
                            if (seconds <= 0) throw AutomationFailure("The timer needs a length")
                            args(
                                "action" to "android.intent.action.SET_TIMER",
                                "extras" to
                                    buildJsonObject {
                                        put("android.intent.extra.alarm.LENGTH", seconds)
                                        put("android.intent.extra.alarm.SKIP_UI", true)
                                        if (p.p("label").isNotBlank()) put("android.intent.extra.alarm.MESSAGE", p.p("label"))
                                    },
                                "returnSnapshot" to false,
                            )
                        },
                    sentence = { p ->
                        "Timer for ${p.p(
                            "minutes",
                        )} min${p.p("seconds").takeIf { it.isNotBlank() && it != "0" }?.let { " $it s" }.orEmpty()}"
                    },
                ),
                ActionDef(
                    type = "alarm.set",
                    category = Category.ALARMS,
                    title = "Set an alarm",
                    description = "Adds an alarm in the clock app without opening it.",
                    fields =
                        listOf(
                            FieldDef("time", "Time", FieldKind.TIME, default = "07:00"),
                            textField("label", "Label", required = false),
                        ),
                    tier = RiskTier.INTERACT,
                    kind =
                        ActionKind.Tool("app.start_activity") { p ->
                            val time = parseTime(p.p("time")) ?: throw AutomationFailure("Give the time as HH:mm")
                            args(
                                "action" to "android.intent.action.SET_ALARM",
                                "extras" to
                                    buildJsonObject {
                                        put("android.intent.extra.alarm.HOUR", time.hour)
                                        put("android.intent.extra.alarm.MINUTES", time.minute)
                                        put("android.intent.extra.alarm.SKIP_UI", true)
                                        if (p.p("label").isNotBlank()) put("android.intent.extra.alarm.MESSAGE", p.p("label"))
                                    },
                                "returnSnapshot" to false,
                            )
                        },
                    sentence = { p -> "Alarm at ${p.p("time")}" },
                ),
                // Notifications
                ActionDef(
                    type = "notify.post",
                    category = Category.NOTIFICATIONS,
                    title = "Show a notification",
                    description = "Posts a notification from PocketPilot.",
                    fields = listOf(textField("title", "Title"), FieldDef("text", "Text", FieldKind.MULTILINE, required = false)),
                    tier = RiskTier.READ,
                    kind = ActionKind.Tool("notify.post") { p -> args("title" to p.p("title"), "text" to p["text"].orEmpty()) },
                    sentence = { p -> "Notify \"${p.p("title")}\"" },
                ),
                ActionDef(
                    type = "notify.act",
                    category = Category.NOTIFICATIONS,
                    title = "Act on a notification",
                    description = "Opens, dismisses, presses a button on or replies to the newest matching notification.",
                    fields =
                        listOf(
                            appsField(),
                            textField("text", "Title or text contains", required = false),
                            textField("action", "Do", default = "dismiss", hint = "open, dismiss, or a button title such as Reply"),
                            textField("reply", "Reply text", required = false),
                        ),
                    tier = RiskTier.SENSITIVE,
                    kind = ActionKind.Engine,
                    tools = setOf("notify.list", "notify.act"),
                    sentence = { p -> "${p.p("action").replaceFirstChar(Char::uppercase)} notification from ${p.appsText()}" },
                ),
                // Screen and UI
                ActionDef(
                    type = "ui.tap_text",
                    category = ui,
                    title = "Tap something on screen",
                    description = "Finds an element by its text or label and taps it.",
                    fields = listOf(textField("text", "Text or label")),
                    tier = RiskTier.INTERACT,
                    kind = ActionKind.Engine,
                    tools = setOf("screen.find", "ui.tap"),
                    sentence = { p -> "Tap \"${p.p("text")}\"" },
                ),
                ActionDef(
                    type = "ui.tap_run",
                    category = ui,
                    title = "Take the first open run in a time window",
                    description = "On a schedule list, taps the first run that starts between the two times on a Monday to Friday.",
                    fields =
                        listOf(
                            textField("from", "Earliest start", default = "06:00", hint = "HH:mm"),
                            textField("to", "Latest start", default = "15:00", hint = "HH:mm"),
                            textField("into", "Save yes or no in variable", default = "run_found", hint = "yes when a run was taken"),
                        ),
                    tier = RiskTier.INTERACT,
                    kind = ActionKind.Engine,
                    tools = setOf("screen.snapshot", "ui.tap"),
                    sentence = { p -> "Take the first Monday to Friday run starting ${p.p("from")} to ${p.p("to")}, if there is one" },
                ),
                ActionDef(
                    type = "ui.global_action",
                    category = ui,
                    title = "Back, Home, Recents and more",
                    description = "A system navigation action.",
                    fields =
                        listOf(
                            choiceField(
                                "action",
                                "Action",
                                listOf(
                                    Choice("home", "Home"),
                                    Choice("back", "Back"),
                                    Choice("recents", "Recent apps"),
                                    Choice("notifications", "Notification shade"),
                                    Choice("quick_settings", "Quick Settings"),
                                    Choice("lock_screen", "Lock the screen"),
                                ),
                            ),
                        ),
                    tier = RiskTier.INTERACT,
                    kind = ActionKind.Tool("ui.global_action") { p -> args("action" to p.p("action"), "returnSnapshot" to false) },
                    sentence = { p -> "Press ${p.p("action").replace('_', ' ')}" },
                ),
                ActionDef(
                    type = "ui.press_key",
                    category = ui,
                    title = "Press a key",
                    description = "Presses a key such as Enter.",
                    fields =
                        listOf(
                            choiceField(
                                "key",
                                "Key",
                                listOf(
                                    Choice("ENTER", "Enter"),
                                    Choice("BACK", "Back"),
                                    Choice("HOME", "Home"),
                                    Choice("DEL", "Delete"),
                                    Choice("TAB", "Tab"),
                                ),
                            ),
                        ),
                    tier = RiskTier.INTERACT,
                    kind = ActionKind.Tool("ui.press_key") { p -> args("key" to p.p("key"), "returnSnapshot" to false) },
                    sentence = { p -> "Press ${p.p("key").lowercase()}" },
                ),
                ActionDef(
                    type = "ui.type_text",
                    category = ui,
                    title = "Type text",
                    description = "Types into the field that has focus.",
                    fields = listOf(FieldDef("text", "Text", FieldKind.MULTILINE)),
                    tier = RiskTier.INTERACT,
                    kind = ActionKind.Tool("ui.type_text") { p -> args("text" to p["text"].orEmpty(), "returnSnapshot" to false) },
                    sentence = { p -> "Type \"${p.p("text").take(30)}\"" },
                ),
                ActionDef(
                    type = "ui.swipe",
                    category = ui,
                    title = "Swipe",
                    description = "Swipes across the middle of the screen, or between two points when all four are set.",
                    fields =
                        listOf(
                            choiceField(
                                "direction",
                                "Direction",
                                listOf(Choice("up", "Up"), Choice("down", "Down"), Choice("left", "Left"), Choice("right", "Right")),
                            ),
                            numberField(
                                "fromX",
                                "From X (pixels)",
                                required = false,
                                hint = "Leave all four points blank to use the direction",
                            ),
                            numberField("fromY", "From Y (pixels)", required = false),
                            numberField("toX", "To X (pixels)", required = false),
                            numberField("toY", "To Y (pixels)", required = false),
                            numberField("durationMs", "Duration (ms)", default = "300", required = false),
                        ),
                    tier = RiskTier.INTERACT,
                    kind =
                        ActionKind.Tool("ui.swipe") { p ->
                            val points = listOf("fromX", "fromY", "toX", "toY").map { p.p(it) }
                            // The tool rejects a direction sent with points, so only the points go when all four are set.
                            if (points.all(String::isNotEmpty)) {
                                args(
                                    "fromX" to points[0],
                                    "fromY" to points[1],
                                    "toX" to points[2],
                                    "toY" to points[3],
                                    "durationMs" to p.p("durationMs"),
                                    "returnSnapshot" to false,
                                )
                            } else {
                                args("direction" to p.p("direction"), "durationMs" to p.p("durationMs"), "returnSnapshot" to false)
                            }
                        },
                    sentence = { p ->
                        if (p.p("fromX").isNotEmpty()) {
                            "Swipe from (${p.p("fromX")}, ${p.p("fromY")}) to (${p.p("toX")}, ${p.p("toY")})"
                        } else {
                            "Swipe ${p.p("direction")}"
                        }
                    },
                ),
                ActionDef(
                    type = "screen.wait_for",
                    category = ui,
                    title = "Wait for something on screen",
                    description = "Waits until text appears on screen; fails after the timeout.",
                    fields = listOf(textField("text", "Text or label"), numberField("seconds", "Timeout (seconds)", default = "10")),
                    tier = RiskTier.READ,
                    kind =
                        ActionKind.Tool("screen.wait_for") { p ->
                            args("text" to p.p("text"), "timeoutMs" to ((p.p("seconds").toLongOrNull() ?: 10).coerceIn(1, 30) * 1000))
                        },
                    sentence = { p -> "Wait for \"${p.p("text")}\"" },
                ),
                ActionDef(
                    type = "screen.read",
                    category = ui,
                    title = "Read the screen",
                    description = "Saves the screen's text in a variable.",
                    fields = listOf(INTO.copy(required = true, default = "screen")),
                    tier = RiskTier.READ,
                    kind = ActionKind.Engine,
                    tools = setOf("screen.snapshot"),
                    sentence = { p -> "Read the screen into {${p.p("into")}}" },
                ),
                ActionDef(
                    type = "screen.wake",
                    category = ui,
                    title = "Turn the screen on",
                    description = "Wakes the screen.",
                    tier = RiskTier.INTERACT,
                    kind = ActionKind.Native,
                ),
                // Sound and media
                ActionDef(
                    type = "media.control",
                    category = Category.MEDIA,
                    title = "Control music",
                    description = "Play, pause or skip in whatever is playing.",
                    fields =
                        listOf(
                            choiceField(
                                "command",
                                "Command",
                                listOf(
                                    Choice("play_pause", "Play or pause"),
                                    Choice("play", "Play"),
                                    Choice("pause", "Pause"),
                                    Choice("next", "Next"),
                                    Choice("previous", "Previous"),
                                    Choice("stop", "Stop"),
                                ),
                            ),
                        ),
                    tier = RiskTier.INTERACT,
                    kind = ActionKind.Native,
                    sentence = { p -> "Music: ${p.p("command").replace('_', ' ')}" },
                ),
                ActionDef(
                    type = "speak",
                    category = Category.SOUND,
                    title = "Speak text",
                    description = "Reads text aloud with the phone's text-to-speech.",
                    fields = listOf(FieldDef("text", "Text", FieldKind.MULTILINE)),
                    tier = RiskTier.READ,
                    kind = ActionKind.Native,
                    sentence = { p -> "Say \"${p.p("text").take(30)}\"" },
                ),
                ActionDef(
                    type = "vibrate",
                    category = Category.SOUND,
                    title = "Vibrate",
                    description = "Vibrates the phone.",
                    fields = listOf(numberField("ms", "Milliseconds", default = "500")),
                    tier = RiskTier.READ,
                    kind = ActionKind.Native,
                    sentence = { p -> "Vibrate ${p.p("ms")} ms" },
                ),
                ActionDef(
                    type = "flashlight",
                    category = Category.DEVICE,
                    title = "Flashlight",
                    description = "Turns the flashlight on or off.",
                    fields = listOf(choiceField("state", "Turn", ON_OFF)),
                    tier = RiskTier.INTERACT,
                    kind = ActionKind.Native,
                    sentence = { p -> "Flashlight ${p.p("state")}" },
                ),
                ActionDef(
                    type = "toast",
                    category = Category.SCREEN,
                    title = "Show a short message",
                    description = "Shows a toast at the bottom of the screen.",
                    fields = listOf(textField("text", "Text")),
                    tier = RiskTier.READ,
                    kind = ActionKind.Native,
                    sentence = { p -> "Show \"${p.p("text").take(30)}\"" },
                ),
                // Files and data
                ActionDef(
                    type = "files.write",
                    category = Category.FILES,
                    title = "Write a file",
                    description = "Writes or appends text to a file in a folder you shared with PocketPilot.",
                    fields =
                        listOf(
                            textField("path", "Path", hint = "Folder/file.txt"),
                            FieldDef("text", "Text", FieldKind.MULTILINE),
                            choiceField("mode", "Mode", listOf(Choice("append", "Append"), Choice("replace", "Replace"))),
                        ),
                    tier = RiskTier.SENSITIVE,
                    kind =
                        ActionKind.Tool("files.write") { p ->
                            val replace = p.p("mode") == "replace"
                            args("path" to p.p("path"), "text" to p["text"].orEmpty(), "replace" to replace, "append" to !replace)
                        },
                    sentence = { p -> "Write to ${p.p("path")}" },
                ),
                ActionDef(
                    type = "files.read",
                    category = Category.FILES,
                    title = "Read a file",
                    description = "Reads a file in a shared folder into a variable.",
                    fields = listOf(textField("path", "Path"), INTO.copy(required = true, default = "file")),
                    tier = RiskTier.SENSITIVE,
                    kind = ActionKind.Engine,
                    tools = setOf("files.read"),
                    sentence = { p -> "Read ${p.p("path")} into {${p.p("into")}}" },
                ),
                ActionDef(
                    type = "http.request",
                    category = Category.FILES,
                    title = "HTTP request",
                    description = "Calls a web address, such as a webhook or Home Assistant, and saves the reply.",
                    fields =
                        listOf(
                            choiceField(
                                "method",
                                "Method",
                                listOf(Choice("GET", "GET"), Choice("POST", "POST"), Choice("PUT", "PUT"), Choice("DELETE", "DELETE")),
                            ),
                            textField("url", "URL"),
                            FieldDef("headers", "Headers", FieldKind.MULTILINE, required = false, hint = "One per line, Name: value"),
                            FieldDef("body", "Body", FieldKind.MULTILINE, required = false),
                            INTO,
                        ),
                    tier = RiskTier.SENSITIVE,
                    kind = ActionKind.Native,
                    sentence = { p -> "${p.p("method")} ${p.p("url")}" },
                ),
                ActionDef(
                    type = "files.copy",
                    category = Category.FILES,
                    title = "Copy or move a file",
                    description = "Copies or moves a file within the folders you shared with PocketPilot.",
                    fields =
                        listOf(
                            textField("from", "From", hint = "Folder/file.txt"),
                            textField("to", "To", hint = "Folder/copy.txt"),
                            choiceField("mode", "Mode", listOf(Choice("copy", "Copy"), Choice("move", "Move"))),
                            choiceField("replace", "Replace an existing file", YES_NO.reversed()),
                        ),
                    tier = RiskTier.SENSITIVE,
                    kind =
                        ActionKind.Tool("files.copy") { p ->
                            args(
                                "from" to p.p("from"),
                                "to" to p.p("to"),
                                "move" to (p.p("mode") == "move"),
                                "replace" to (p.p("replace") == "yes"),
                            )
                        },
                    sentence = { p -> "${if (p.p("mode") == "move") "Move" else "Copy"} ${p.p("from")} to ${p.p("to")}" },
                ),
                ActionDef(
                    type = "files.delete",
                    category = Category.FILES,
                    title = "Delete a file",
                    description = "Deletes one file in a shared folder. Asks you each time.",
                    fields = listOf(textField("path", "Path", hint = "Folder/file.txt")),
                    tier = RiskTier.SENSITIVE,
                    kind = ActionKind.Tool("files.delete") { p -> args("path" to p.p("path")) },
                    sentence = { p -> "Delete ${p.p("path")}" },
                ),
                ActionDef(
                    type = "http.download",
                    category = Category.FILES,
                    title = "Download a file",
                    description = "Saves a web address to a new file in a shared folder; never replaces a file. Up to 50 MB.",
                    fields = listOf(textField("url", "URL"), textField("path", "Save as", hint = "Folder/file.pdf"), INTO),
                    tier = RiskTier.SENSITIVE,
                    kind = ActionKind.Native,
                    sentence = { p -> "Download to ${p.p("path")}" },
                ),
                ActionDef(
                    type = "json.pick",
                    category = Category.VARIABLES,
                    title = "Read a value from JSON",
                    description =
                        "Takes one value out of JSON text, such as an HTTP reply, with a path like data.items[0].name.",
                    fields =
                        listOf(
                            FieldDef("json", "JSON", FieldKind.MULTILINE, hint = "Usually a variable, like {reply}"),
                            textField("path", "Path", hint = "For example current.temperature"),
                            INTO.copy(required = true, default = "value"),
                        ),
                    tier = RiskTier.READ,
                    kind = ActionKind.Engine,
                    sentence = { p -> "{${p.p("into")}} = ${p.p("path")}" },
                ),
                // Home Assistant
                ActionDef(
                    type = "ha.call",
                    category = Category.HOME,
                    title = "Home Assistant action",
                    description = "Calls a Home Assistant action, such as light.turn_on, on an entity.",
                    fields =
                        listOf(
                            textField("service", "Action", hint = "For example light.turn_on"),
                            textField("entity", "Entity ID", required = false, hint = "For example light.kitchen"),
                            FieldDef(
                                "data",
                                "Extra data (JSON)",
                                FieldKind.JSON,
                                required = false,
                                hint = "For example {\"brightness_pct\": 50}",
                            ),
                            INTO,
                        ),
                    tier = RiskTier.SENSITIVE,
                    kind = ActionKind.Native,
                    sentence = { p -> listOf(p.p("service"), p.p("entity")).filter(String::isNotBlank).joinToString(" ") },
                ),
                ActionDef(
                    type = "ha.get",
                    category = Category.HOME,
                    title = "Read a Home Assistant entity",
                    description = "Saves an entity's state, or one of its attributes, in a variable.",
                    fields =
                        listOf(
                            textField("entity", "Entity ID", hint = "For example sensor.outside_temperature"),
                            textField("attribute", "Attribute", required = false, hint = "The state when empty"),
                            INTO.copy(required = true, default = "state"),
                        ),
                    tier = RiskTier.READ,
                    kind = ActionKind.Native,
                    sentence = { p -> "{${p.p("into")}} = ${p.p("entity")}" },
                ),
                // Calendar
                ActionDef(
                    type = "calendar.add",
                    category = Category.CALENDAR,
                    title = "Add a calendar event",
                    description = "Adds an event to your calendar. Needs calendar access.",
                    fields =
                        listOf(
                            textField("title", "Title"),
                            textField("start", "Starts", hint = "2026-10-20 14:30, or 14:30 for today"),
                            numberField("minutes", "Length (minutes)", default = "60"),
                            textField("location", "Place", required = false),
                            textField("calendar", "Calendar", required = false, hint = "The main calendar when empty"),
                        ),
                    tier = RiskTier.SENSITIVE,
                    kind = ActionKind.Native,
                    sentence = { p -> "Add \"${p.p("title").take(30)}\" at ${p.p("start")}" },
                ),
                ActionDef(
                    type = "calendar.read",
                    category = Category.CALENDAR,
                    title = "Read upcoming events",
                    description = "Saves the next events, one per line as \"start, title\", in a variable. Needs calendar access.",
                    fields =
                        listOf(
                            numberField("hours", "Next (hours)", default = "24"),
                            textField("title", "Title contains", required = false),
                            INTO.copy(required = true, default = "events"),
                        ),
                    tier = RiskTier.SENSITIVE,
                    kind = ActionKind.Native,
                    sentence = { p -> "Read the next ${p.p("hours")} hours of events" },
                ),
                ActionDef(
                    type = "adb.query",
                    category = Category.FILES,
                    title = "Read phone state (adb)",
                    description = "Runs a read-only adb command, such as dumpsys battery, and saves its output.",
                    fields = listOf(textField("command", "Command"), INTO.copy(required = true, default = "output")),
                    tier = RiskTier.READ,
                    kind = ActionKind.Engine,
                    tools = setOf("adb.query"),
                    sentence = { p -> "Run ${p.p("command")}" },
                ),
                ActionDef(
                    type = "shell.exec",
                    category = Category.FILES,
                    title = "Run a shell command",
                    description = "Runs an adb shell command through Shizuku. Needs the Shell switch; asks once per exact command.",
                    fields = listOf(textField("command", "Command"), INTO),
                    tier = RiskTier.SENSITIVE,
                    kind = ActionKind.Engine,
                    tools = setOf("shell.exec"),
                    sideloadOnly = true,
                    sentence = { p -> "Shell: ${p.p("command")}" },
                ),
                ActionDef(
                    type = "tool.call",
                    category = Category.POCKETPILOT,
                    title = "Call any tool",
                    description = "Calls any PocketPilot tool with JSON arguments.",
                    fields =
                        listOf(
                            FieldDef("tool", "Tool", FieldKind.TOOL),
                            FieldDef("arguments", "Arguments (JSON)", FieldKind.JSON, required = false, default = "{}"),
                            INTO,
                        ),
                    tier = RiskTier.SENSITIVE,
                    kind = ActionKind.Engine,
                    sentence = { p -> "Call ${p.p("tool")}" },
                ),
                // AI
                ActionDef(
                    type = "agent.ask",
                    category = Category.AI,
                    title = "Ask Agent to do a task",
                    description = "Hands a task to Agent mode, which works the phone like you would. Counts against the monthly AI limit.",
                    fields = listOf(FieldDef("prompt", "Task", FieldKind.MULTILINE), INTO),
                    tier = RiskTier.SENSITIVE,
                    kind = ActionKind.Engine,
                    sentence = { p -> "Agent: ${p.p("prompt").take(40)}" },
                ),
                // Flow
                ActionDef(
                    type = "flow.if",
                    category = Category.FLOW,
                    title = "If",
                    description = "Runs the inner actions when its conditions hold, the Else actions otherwise.",
                    tier = RiskTier.READ,
                    kind = ActionKind.Engine,
                    container = true,
                    conditional = true,
                ),
                ActionDef(
                    type = "flow.repeat",
                    category = Category.FLOW,
                    title = "Repeat",
                    description = "Runs the inner actions a number of times; {loop.index} counts from 1.",
                    fields = listOf(numberField("count", "Times", default = "3")),
                    tier = RiskTier.READ,
                    kind = ActionKind.Engine,
                    container = true,
                    sentence = { p -> "Repeat ${p.p("count")} times" },
                ),
                ActionDef(
                    type = "flow.while",
                    category = Category.FLOW,
                    title = "While",
                    description = "Runs the inner actions while its conditions hold, at most a set number of times.",
                    fields = listOf(numberField("max", "At most (times)", default = "20")),
                    tier = RiskTier.READ,
                    kind = ActionKind.Engine,
                    container = true,
                    conditional = true,
                    sentence = { p -> "While (at most ${p.p("max")} times)" },
                ),
                ActionDef(
                    type = "flow.wait",
                    category = Category.FLOW,
                    title = "Wait",
                    description = "Pauses the rule.",
                    fields = listOf(numberField("seconds", "Seconds", default = "5")),
                    tier = RiskTier.READ,
                    kind = ActionKind.Engine,
                    sentence = { p -> "Wait ${p.p("seconds")} s" },
                ),
                ActionDef(
                    type = "flow.wait_until",
                    category = Category.FLOW,
                    title = "Wait until",
                    description = "Waits until its conditions hold, checking every few seconds; fails after the timeout.",
                    fields = listOf(numberField("timeout", "Timeout (seconds)", default = "60")),
                    tier = RiskTier.READ,
                    kind = ActionKind.Engine,
                    conditional = true,
                    sentence = { p -> "Wait until (up to ${p.p("timeout")} s)" },
                ),
                ActionDef(
                    type = "flow.stop",
                    category = Category.FLOW,
                    title = "Stop this rule",
                    description = "Ends the run here.",
                    tier = RiskTier.READ,
                    kind = ActionKind.Engine,
                ),
                ActionDef(
                    type = "flow.run_rule",
                    category = Category.FLOW,
                    title = "Run another rule",
                    description = "Runs another rule's actions (its constraints are checked first) and waits for it.",
                    fields = listOf(FieldDef("rule", "Rule", FieldKind.RULE)),
                    tier = RiskTier.READ,
                    kind = ActionKind.Engine,
                    sentence = { "Run another rule" },
                ),
                ActionDef(
                    type = "flow.set_rule",
                    category = Category.FLOW,
                    title = "Turn a rule on or off",
                    description = "Enables or disables a rule.",
                    fields = listOf(FieldDef("rule", "Rule", FieldKind.RULE), choiceField("state", "Turn", ON_OFF)),
                    tier = RiskTier.READ,
                    kind = ActionKind.Engine,
                    sentence = { p -> "Turn a rule ${p.p("state")}" },
                ),
                ActionDef(
                    type = "flow.log",
                    category = Category.FLOW,
                    title = "Log a line",
                    description = "Adds a line to this run in the Activity tab.",
                    fields = listOf(textField("text", "Text")),
                    tier = RiskTier.READ,
                    kind = ActionKind.Engine,
                    sentence = { p -> "Log \"${p.p("text").take(30)}\"" },
                ),
                // Variables
                ActionDef(
                    type = "var.set",
                    category = Category.VARIABLES,
                    title = "Set a variable",
                    description = "Local variables last for this run; global ones are kept and other rules can read them.",
                    fields =
                        listOf(
                            textField("name", "Name"),
                            FieldDef("value", "Value", FieldKind.MULTILINE, required = false),
                            choiceField("scope", "Kind", listOf(Choice("local", "Local"), Choice("global", "Global"))),
                        ),
                    tier = RiskTier.READ,
                    kind = ActionKind.Engine,
                    sentence = { p -> "{${p.p("name")}} = ${p.p("value").take(30)}" },
                ),
                ActionDef(
                    type = "var.math",
                    category = Category.VARIABLES,
                    title = "Calculate",
                    description = "Adds, subtracts, multiplies or divides a variable.",
                    fields =
                        listOf(
                            textField("name", "Variable"),
                            choiceField(
                                "op",
                                "Operation",
                                listOf(
                                    Choice("add", "Add"),
                                    Choice("subtract", "Subtract"),
                                    Choice("multiply", "Multiply by"),
                                    Choice("divide", "Divide by"),
                                ),
                            ),
                            textField("value", "Number", default = "1"),
                        ),
                    tier = RiskTier.READ,
                    kind = ActionKind.Engine,
                    sentence = { p -> "{${p.p("name")}} ${p.p("op")} ${p.p("value")}" },
                ),
                ActionDef(
                    type = "var.text",
                    category = Category.VARIABLES,
                    title = "Change text",
                    description = "Replaces, extracts or changes the case of a text variable.",
                    fields =
                        listOf(
                            textField("name", "Variable"),
                            choiceField(
                                "op",
                                "Operation",
                                listOf(
                                    Choice("replace", "Replace"),
                                    Choice("extract", "Extract with regex"),
                                    Choice("upper", "Upper case"),
                                    Choice("lower", "Lower case"),
                                    Choice("trim", "Trim"),
                                    Choice("length", "Length"),
                                ),
                            ),
                            textField("find", "Find or regex", required = false),
                            textField("with", "Replace with", required = false),
                        ),
                    tier = RiskTier.READ,
                    kind = ActionKind.Engine,
                    sentence = { p -> "${p.p("op").replaceFirstChar(Char::uppercase)} {${p.p("name")}}" },
                ),
                ActionDef(
                    type = "var.random",
                    category = Category.VARIABLES,
                    title = "Random number",
                    description = "A whole number between two values.",
                    fields =
                        listOf(
                            textField("name", "Variable"),
                            numberField("min", "From", default = "1"),
                            numberField("max", "To", default = "6"),
                        ),
                    tier = RiskTier.READ,
                    kind = ActionKind.Engine,
                    sentence = { p -> "{${p.p("name")}} = random ${p.p("min")} to ${p.p("max")}" },
                ),
            )

    /** Reads a tool's arguments given as text, for [ActionKind.Engine] steps that call any tool. */
    fun parseArguments(text: String): JsonObject =
        if (text.isBlank()) {
            JsonObject(emptyMap())
        } else {
            runCatching { AutomationJson.json.parseToJsonElement(text).jsonObject }.getOrNull()
                ?: throw AutomationFailure("Arguments must be a JSON object")
        }
}
