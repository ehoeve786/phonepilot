package app.pocketpilot.core.model

import kotlinx.serialization.json.JsonObject

/** One request to run a tool, made by a [Session] (spec section 4, call lifecycle step 1). */
data class ToolCall(
    val callId: String,
    val sessionId: SessionId,
    val tool: String,
    val arguments: JsonObject,
)
