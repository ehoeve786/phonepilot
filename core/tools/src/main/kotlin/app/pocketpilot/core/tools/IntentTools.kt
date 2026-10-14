package app.pocketpilot.core.tools

import app.pocketpilot.capability.api.apps.ActivityEntry
import app.pocketpilot.capability.api.apps.AppAdmin
import app.pocketpilot.capability.api.apps.IntentArgs
import app.pocketpilot.capability.api.apps.IntentExtra
import app.pocketpilot.capability.api.screen.ScreenReader
import app.pocketpilot.core.model.RiskTier
import app.pocketpilot.core.model.Session
import app.pocketpilot.core.model.ToolCall
import app.pocketpilot.core.model.ToolResult
import app.pocketpilot.core.orchestrator.ToolHandler
import app.pocketpilot.core.tools.SchemaParts.RETURN_SNAPSHOT
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

private const val INTENT_PROPS =
    """"component":{"type":"string","description":"package/class from app.activities, for example com.example/.MainActivity"},
    "package":{"type":"string","description":"The app to send an implicit intent to, when there is no component"},
    "action":{"type":"string","description":"For example android.intent.action.VIEW"},
    "data":{"type":"string","description":"A URI, for example geo:0,0?q=coffee"},
    "mimeType":{"type":"string"},
    "categories":{"type":"array","items":{"type":"string"}},
    "extras":{"type":"object","description":"Extras by key; strings, booleans, whole numbers (int or long) and decimals (float)",
    "additionalProperties":{"type":["string","number","boolean"]}}"""

/** Builds the `am` arguments from a tool call; invalid arguments become INVALID_ARGUMENTS. */
private fun ToolCall.intentArgs(): List<String> {
    val categories =
        (arguments["categories"] as? JsonArray)?.map {
            (it as? JsonPrimitive)?.content ?: invalid(
                "categories must be strings",
            )
        }
    val extras =
        (arguments["extras"] as? JsonObject)?.map { (key, value) ->
            val primitive = value as? JsonPrimitive ?: invalid("Extra $key must be a string, number or boolean")
            when {
                primitive.isString -> {
                    IntentExtra(key, "string", primitive.content)
                }

                primitive.booleanOrNull != null -> {
                    IntentExtra(key, "bool", primitive.content)
                }

                primitive.longOrNull != null -> {
                    IntentExtra(key, if (primitive.intOrNull != null) "int" else "long", primitive.content)
                }

                primitive.doubleOrNull != null -> {
                    IntentExtra(key, "float", primitive.content)
                }

                else -> {
                    invalid("Extra $key must be a string, number or boolean")
                }
            }
        }
    return try {
        IntentArgs.build(
            component = arguments.string("component"),
            packageName = arguments.string("package"),
            action = arguments.string("action"),
            data = arguments.string("data"),
            mimeType = arguments.string("mimeType"),
            categories = categories.orEmpty(),
            extras = extras.orEmpty(),
        )
    } catch (e: IllegalArgumentException) {
        invalid(e.message ?: "Not a valid intent")
    }
}

private fun ToolCall.intentPackage(): String? = arguments.string("component")?.substringBefore('/') ?: arguments.string("package")

private fun ToolCall.intentSummary(): String =
    listOfNotNull(
        arguments.string("component") ?: arguments.string("package"),
        arguments.string("action"),
        arguments.string("data"),
        (arguments["extras"] as? JsonObject)?.let { "extras $it" },
    ).joinToString(" ")

/** `app.activities`: what an app can be opened at directly. */
class AppActivitiesTool(
    private val admin: AppAdmin,
) : ToolHandler {
    override val spec =
        adminSpec(
            "app.activities",
            "List app activities",
            "Lists the screens (activities) an app lets other apps open, with the actions, categories and URI schemes " +
                "each answers, one per line. Use it to find a screen to open directly with app.start_activity, for " +
                "example a clock app's stopwatch, instead of tapping through the app.",
            """$PACKAGE_PROP,"filter":{"type":"string","description":"Only activities whose name or actions contain this"}""",
            "\"package\"",
            RiskTier.READ,
            readOnly = true,
        )

    override suspend fun execute(
        call: ToolCall,
        session: Session,
    ): ToolResult {
        val pkg = call.arguments.packageName()
        val filter = call.arguments.string("filter")
        val all = admin.activities(pkg)
        val shown = filter?.let { f -> all.filter { it.matches(f) } } ?: all
        if (shown.isEmpty()) {
            val why = if (all.isEmpty()) "$pkg exposes no activities with intent filters." else "No activity of $pkg matches \"$filter\"."
            return ToolResult.success(why)
        }
        return ToolResult.success(shown.joinToString("\n") { it.line() })
    }

    private fun ActivityEntry.matches(filter: String): Boolean =
        component.contains(filter, ignoreCase = true) || actions.any { it.contains(filter, ignoreCase = true) }

    private fun ActivityEntry.line(): String =
        buildString {
            append(component)
            if (actions.isNotEmpty()) append(" actions=").append(actions.joinToString(",") { it.removePrefix("android.intent.action.") })
            if (categories.isNotEmpty()) {
                append(
                    " categories=",
                ).append(categories.joinToString(",") { it.removePrefix("android.intent.category.") })
            }
            if (schemes.isNotEmpty()) append(" schemes=").append(schemes.joinToString(","))
        }
}

/** `app.start_activity`: opens an app at a specific screen with an intent. */
class AppStartActivityTool(
    private val admin: AppAdmin,
    private val reader: ScreenReader,
) : ToolHandler {
    override val spec =
        adminSpec(
            "app.start_activity",
            "Open app screen",
            "Opens a specific screen of an app with an intent (am start), for example a clock app's stopwatch or a " +
                "Settings page, so you do not have to navigate there. Give component (from app.activities) or package " +
                "with an action. Short actions in app.activities output are android.intent.action.* and categories " +
                "android.intent.category.*. Standard intents most apps answer: android.intent.action.SET_TIMER (extras " +
                "android.intent.extra.alarm.LENGTH in seconds, android.intent.extra.alarm.SKIP_UI true) and SET_ALARM " +
                "(android.intent.extra.alarm.HOUR, MINUTES, MESSAGE, SKIP_UI) to the clock app; SHOW_TIMERS, SHOW_ALARMS; " +
                "VIEW with a geo:, tel: or https: URI. Returns the screen once it opened.",
            "$INTENT_PROPS,$RETURN_SNAPSHOT",
            "",
            RiskTier.INTERACT,
        )

    override suspend fun targetPackage(call: ToolCall): String? = call.intentPackage()

    override suspend fun execute(
        call: ToolCall,
        session: Session,
    ): ToolResult {
        val args = call.intentArgs()
        val pkg = IntentArgs.targetPackage(args)
        val output = admin.startIntent(args, broadcast = false)
        if (reader.available.value && pkg != null) {
            withTimeoutOrNull(START_WAIT_MS) {
                while (reader.foreground()?.packageName != pkg) reader.awaitChange(START_WAIT_MS)
            }
        }
        val summary = "Started ${call.intentSummary()}.\n${output.lines().filter {
            it.startsWith(
                "Status",
            ) || it.startsWith("Activity") || it.startsWith("Warning")
        }.joinToString("\n")}"
        return afterAction(reader, summary.trimEnd(), call.arguments.boolean("returnSnapshot") ?: true)
    }

    private companion object {
        const val START_WAIT_MS = 5_000L
    }
}

/** `app.send_broadcast`: sends an intent to an app's broadcast receivers, for automation apps. */
class AppSendBroadcastTool(
    private val admin: AppAdmin,
) : ToolHandler {
    override val spec =
        adminSpec(
            "app.send_broadcast",
            "Send broadcast",
            "Sends a broadcast intent (am broadcast) to one app, for example to trigger a Tasker or Termux action " +
                "that listens for it. Give component or package, and usually an action.",
            INTENT_PROPS,
            "",
            RiskTier.SENSITIVE,
        )

    override suspend fun targetPackage(call: ToolCall): String? = call.intentPackage()

    override suspend fun confirmationDetail(call: ToolCall): String = "Send broadcast ${call.intentSummary()}"

    override suspend fun execute(
        call: ToolCall,
        session: Session,
    ): ToolResult {
        val output = admin.startIntent(call.intentArgs(), broadcast = true)
        return ToolResult.success(output.lines().lastOrNull { it.isNotBlank() } ?: "Broadcast sent.")
    }
}
