package app.pocketpilot.core.tools

import app.pocketpilot.capability.api.screen.ScreenReader
import app.pocketpilot.capability.api.screen.Snapshot
import app.pocketpilot.capability.api.screen.Target
import app.pocketpilot.core.model.ToolErrorCode
import app.pocketpilot.core.model.ToolException
import app.pocketpilot.core.model.ToolResult
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/** Compact JSON for snapshots: defaults and nulls are left out. */
internal val compactJson =
    Json {
        encodeDefaults = false
        explicitNulls = false
    }

internal fun schema(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

internal fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull

internal fun JsonObject.long(name: String): Long? = (this[name] as? JsonPrimitive)?.longOrNull

internal fun JsonObject.boolean(name: String): Boolean? = (this[name] as? JsonPrimitive)?.booleanOrNull

internal fun invalid(message: String): Nothing = throw ToolException(ToolErrorCode.INVALID_ARGUMENTS, message)

/** Reads `element` + `snapshotId`, or `x` + `y`, into a [Target]. */
internal fun JsonObject.target(): Target {
    val element = string("element")
    val x = int("x")
    val y = int("y")
    return when {
        element != null && (x != null || y != null) -> invalid("Give either element or x and y, not both")
        element != null -> Target.OnElement(element, string("snapshotId"))
        x != null && y != null -> Target.AtPoint(x, y)
        else -> invalid("Give element (an ID from screen.snapshot) or both x and y in screen pixels")
    }
}

internal fun JsonObject.elementTarget(): Target.OnElement? = string("element")?.let { Target.OnElement(it, string("snapshotId")) }

internal fun Snapshot.toJson(): String = compactJson.encodeToString(Snapshot.serializer(), this)

/**
 * The result of an interacting tool: a short description, then (unless the caller opted out) the
 * screen after it settled, so the model can plan its next step without another call.
 */
internal suspend fun afterAction(
    reader: ScreenReader,
    summary: String,
    withSnapshot: Boolean,
): ToolResult {
    if (!withSnapshot || !reader.available.value) return ToolResult.success(summary)
    // Give the app a moment to react, then wait for a longer quiet spell than the 300 ms idle default:
    // pages that load in steps (Samsung Settings) pause briefly between steps.
    delay(REACTION_MS)
    reader.awaitIdle(quietMs = SETTLE_QUIET_MS, timeoutMs = SETTLE_TIMEOUT_MS)
    val snapshot = reader.snapshot()
    return ToolResult.success("$summary\nScreen now:\n${snapshot.toJson()}")
}

private const val REACTION_MS = 150L
private const val SETTLE_QUIET_MS = 600L
private const val SETTLE_TIMEOUT_MS = 4_000L

/** JSON Schema fragments shared by the ui.* tools. */
internal object SchemaParts {
    const val ELEMENT = """"element":{"type":"string","description":"Element ID from the latest screen.snapshot, for example e12"}"""
    const val SNAPSHOT_ID =
        """"snapshotId":{"type":"string","description":"The snapshot the element ID came from; defaults to the latest"}"""
    const val X = """"x":{"type":"integer","description":"Screen x in pixels"}"""
    const val Y = """"y":{"type":"integer","description":"Screen y in pixels"}"""
    const val RETURN_SNAPSHOT =
        """"returnSnapshot":{"type":"boolean","description":"Return the screen after the action settles (default true)"}"""
}
