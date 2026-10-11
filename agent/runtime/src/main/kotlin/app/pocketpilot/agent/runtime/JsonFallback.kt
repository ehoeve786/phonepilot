package app.pocketpilot.agent.runtime

import app.pocketpilot.agent.providers.api.ToolDef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Tool calling for models without it (spec section 9, JSON fallback): the model replies with one
 * `{"action": "<tool>", "args": {...}}` object per step, or `{"action": "done", "args": {"summary": ...}}`.
 */
internal object JsonFallback {
    const val DONE = "done"

    sealed interface Parsed {
        data class Call(
            val action: String,
            val args: JsonObject,
        ) : Parsed

        data class Done(
            val summary: String,
        ) : Parsed

        data class Invalid(
            val problem: String,
        ) : Parsed
    }

    fun instructions(tools: List<ToolDef>): String =
        buildString {
            appendLine("You call tools by replying with exactly one JSON object and nothing else:")
            appendLine("""{"action": "<tool name>", "args": { ...arguments... }}""")
            appendLine("""When finished, reply {"action": "$DONE", "args": {"summary": "<what you did>"}}.""")
            appendLine("The result of each action comes back in the next message. Tools:")
            for (tool in tools) {
                appendLine("- ${tool.name}: ${tool.description}")
                appendLine("  arguments schema: ${tool.inputSchema}")
            }
        }.trimEnd()

    /** Finds the action object in [text], tolerating code fences and prose around it. */
    fun parse(text: String): Parsed {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return Parsed.Invalid("no JSON object found")
        val obj =
            try {
                Json.parseToJsonElement(text.substring(start, end + 1)).jsonObject
            } catch (e: IllegalArgumentException) {
                return Parsed.Invalid("not valid JSON: ${e.message?.lineSequence()?.firstOrNull()}")
            }
        val action =
            (obj["action"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
                ?: return Parsed.Invalid("missing \"action\"")
        val args: JsonElement = obj["args"] ?: JsonObject(emptyMap())
        if (args !is JsonObject) return Parsed.Invalid("\"args\" must be an object")
        if (action == DONE) {
            return Parsed.Done((args["summary"] as? JsonPrimitive)?.contentOrNull.orEmpty())
        }
        return Parsed.Call(action, args)
    }

    fun retryMessage(problem: String): String =
        "I could not read your reply ($problem). Reply with exactly one JSON object: " +
            """{"action": "<tool name>", "args": {...}} or {"action": "$DONE", "args": {"summary": "..."}}."""
}
