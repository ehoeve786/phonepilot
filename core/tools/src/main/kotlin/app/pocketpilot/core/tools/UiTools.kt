package app.pocketpilot.core.tools

import app.pocketpilot.capability.api.screen.Bounds
import app.pocketpilot.capability.api.screen.GlobalAction
import app.pocketpilot.capability.api.screen.InputController
import app.pocketpilot.capability.api.screen.Key
import app.pocketpilot.capability.api.screen.ScreenReader
import app.pocketpilot.capability.api.screen.SwipeDirection
import app.pocketpilot.capability.api.screen.Target
import app.pocketpilot.capability.api.screen.swipePoints
import app.pocketpilot.core.model.Availability
import app.pocketpilot.core.model.CapabilityId
import app.pocketpilot.core.model.Phase
import app.pocketpilot.core.model.RiskTier
import app.pocketpilot.core.model.Scope
import app.pocketpilot.core.model.Session
import app.pocketpilot.core.model.ToolCall
import app.pocketpilot.core.model.ToolResult
import app.pocketpilot.core.model.ToolSpec
import app.pocketpilot.core.orchestrator.ToolHandler
import app.pocketpilot.core.tools.SchemaParts.ELEMENT
import app.pocketpilot.core.tools.SchemaParts.RETURN_SNAPSHOT
import app.pocketpilot.core.tools.SchemaParts.SNAPSHOT_ID
import app.pocketpilot.core.tools.SchemaParts.X
import app.pocketpilot.core.tools.SchemaParts.Y

private fun interactSpec(
    name: String,
    title: String,
    description: String,
    properties: String,
    required: String = "",
) = ToolSpec(
    name = name,
    title = title,
    description = description,
    inputSchema = schema("""{"type":"object","properties":{$properties},$required"additionalProperties":false}"""),
    riskTier = RiskTier.INTERACT,
    requiredScopes = setOf(Scope.UI_INTERACT),
    requiredCapabilities = setOf(CapabilityId.INJECT_INPUT),
    availability = Availability(Phase.P0),
)

private fun Target.describe(): String =
    when (this) {
        is Target.OnElement -> "element $elementId"
        is Target.AtPoint -> "($x, $y)"
    }

/** `ui.tap`. */
class UiTapTool(
    private val input: InputController,
    private val reader: ScreenReader,
) : ToolHandler {
    override val spec =
        interactSpec(
            name = "ui.tap",
            title = "Tap",
            description =
                "Taps an element from the latest screen.snapshot (preferred) or a point in screen pixels. Tapping a " +
                    "text label taps its clickable row. Returns the screen after it settles.",
            properties = "$ELEMENT,$SNAPSHOT_ID,$X,$Y,$RETURN_SNAPSHOT",
        )

    override suspend fun execute(
        call: ToolCall,
        session: Session,
    ): ToolResult {
        val target = call.arguments.target()
        input.tap(target)
        return afterAction(reader, "Tapped ${target.describe()}.", call.arguments.boolean("returnSnapshot") ?: true)
    }
}

/** `ui.long_press`. */
class UiLongPressTool(
    private val input: InputController,
    private val reader: ScreenReader,
) : ToolHandler {
    override val spec =
        interactSpec(
            name = "ui.long_press",
            title = "Long press",
            description = "Long-presses an element or a point in screen pixels. durationMs defaults to 600.",
            properties = """$ELEMENT,$SNAPSHOT_ID,$X,$Y,"durationMs":{"type":"integer"},$RETURN_SNAPSHOT""",
        )

    override suspend fun execute(
        call: ToolCall,
        session: Session,
    ): ToolResult {
        val target = call.arguments.target()
        val duration = (call.arguments.long("durationMs") ?: 600L).coerceIn(100, 10_000)
        input.longPress(target, duration)
        return afterAction(reader, "Long-pressed ${target.describe()}.", call.arguments.boolean("returnSnapshot") ?: true)
    }
}

/** `ui.swipe`: a straight swipe between two points, or a swipe across an element to scroll it. */
class UiSwipeTool(
    private val input: InputController,
    private val reader: ScreenReader,
) : ToolHandler {
    override val spec =
        interactSpec(
            name = "ui.swipe",
            title = "Swipe",
            description =
                "Swipes from (fromX, fromY) to (toX, toY) in screen pixels, across an element in a direction, or " +
                    "across the middle of the screen when only direction is given. " +
                    "direction is where the finger moves: to scroll a list down to later items, swipe up. durationMs " +
                    "defaults to 300.",
            properties =
                """"fromX":{"type":"integer"},"fromY":{"type":"integer"},"toX":{"type":"integer"},"toY":{"type":"integer"},
                $ELEMENT,$SNAPSHOT_ID,"direction":{"type":"string","enum":["up","down","left","right"]},
                "durationMs":{"type":"integer"},$RETURN_SNAPSHOT""",
        )

    override suspend fun execute(
        call: ToolCall,
        session: Session,
    ): ToolResult {
        val args = call.arguments
        val duration = (args.long("durationMs") ?: 300L).coerceIn(50, 5_000)
        val element = args.elementTarget()
        val direction = args.string("direction")?.let { SwipeDirection.valueOf(it.uppercase()) }
        val points = listOf("fromX", "fromY", "toX", "toY").map { args.int(it) }
        val summary =
            when {
                element != null && direction != null -> {
                    input.swipeOn(element, direction, duration)
                    "Swiped ${direction.name.lowercase()} on element ${element.elementId}."
                }

                element == null && direction != null && points.all { it == null } -> {
                    val screen = reader.snapshot().screen
                    val (fromX, fromY, toX, toY) = swipePoints(Bounds(0, 0, screen.width, screen.height), direction).toList()
                    input.swipe(fromX, fromY, toX, toY, duration)
                    "Swiped ${direction.name.lowercase()} across the screen."
                }

                element == null && direction == null && points.all { it != null } -> {
                    val (fromX, fromY, toX, toY) = points.map { it!! }
                    input.swipe(fromX, fromY, toX, toY, duration)
                    "Swiped from ($fromX, $fromY) to ($toX, $toY)."
                }

                else -> {
                    invalid(
                        "Give fromX, fromY, toX and toY; or a direction, with an element to swipe on or without one for the whole screen",
                    )
                }
            }
        return afterAction(reader, summary, args.boolean("returnSnapshot") ?: true)
    }
}

/** `ui.type_text`. */
class UiTypeTextTool(
    private val input: InputController,
    private val reader: ScreenReader,
) : ToolHandler {
    override val spec =
        interactSpec(
            name = "ui.type_text",
            title = "Type text",
            description =
                "Sets the text of a text field (element), or of the focused field when element is left out. This " +
                    "replaces what the field held. submit presses the keyboard's action key (search, send, done) after.",
            properties =
                """"text":{"type":"string"},$ELEMENT,$SNAPSHOT_ID,"submit":{"type":"boolean"},$RETURN_SNAPSHOT""",
            required = """"required":["text"],""",
        )

    override suspend fun execute(
        call: ToolCall,
        session: Session,
    ): ToolResult {
        val text = call.arguments.string("text") ?: invalid("text is required")
        val submit = call.arguments.boolean("submit") ?: false
        val target = call.arguments.elementTarget()
        input.typeText(text, target, submit)
        val where = target?.let { "element ${it.elementId}" } ?: "the focused field"
        val summary = "Typed ${text.length} characters into $where${if (submit) " and submitted" else ""}."
        return afterAction(reader, summary, call.arguments.boolean("returnSnapshot") ?: true)
    }
}

/** `ui.press_key`. */
class UiPressKeyTool(
    private val input: InputController,
    private val reader: ScreenReader,
) : ToolHandler {
    override val spec =
        interactSpec(
            name = "ui.press_key",
            title = "Press key",
            description =
                "Presses BACK, HOME, ENTER (the keyboard action key in the focused field), DEL (deletes the last " +
                    "character of the focused field) or TAB (moves focus to the next field).",
            properties = """"key":{"type":"string","enum":[${Key.entries.joinToString(",") { "\"${it.name}\"" }}]},$RETURN_SNAPSHOT""",
            required = """"required":["key"],""",
        )

    override suspend fun execute(
        call: ToolCall,
        session: Session,
    ): ToolResult {
        val key = Key.valueOf(call.arguments.string("key") ?: invalid("key is required"))
        input.pressKey(key)
        return afterAction(reader, "Pressed $key.", call.arguments.boolean("returnSnapshot") ?: true)
    }
}

/** `ui.global_action`. */
class UiGlobalActionTool(
    private val input: InputController,
    private val reader: ScreenReader,
) : ToolHandler {
    override val spec =
        interactSpec(
            name = "ui.global_action",
            title = "System action",
            description =
                "Runs a system navigation action: back, home, recents, notifications (opens the shade), " +
                    "quick_settings or lock_screen.",
            properties =
                """"action":{"type":"string","enum":[${GlobalAction.entries.joinToString(",") { "\"${it.name.lowercase()}\"" }}]},
                $RETURN_SNAPSHOT""",
            required = """"required":["action"],""",
        )

    override suspend fun execute(
        call: ToolCall,
        session: Session,
    ): ToolResult {
        val action = GlobalAction.valueOf((call.arguments.string("action") ?: invalid("action is required")).uppercase())
        input.globalAction(action)
        return afterAction(reader, "Ran ${action.name.lowercase()}.", call.arguments.boolean("returnSnapshot") ?: true)
    }
}
