package app.pocketpilot.core.audit

import kotlinx.serialization.json.JsonObject

enum class AuditDecision {
    ALLOW,
    DENY,
}

enum class AuditOutcome {
    SUCCESS,
    ERROR,
    DENIED,
}

/** One record per tool call, written whatever the outcome (spec section 4). */
data class AuditEvent(
    val id: String,
    val timestampMillis: Long,
    val sessionId: String,
    val principal: String,
    val tool: String,
    /** Sensitive fields replaced by their length and hash; see [Redactor]. */
    val argumentsRedacted: JsonObject,
    val decision: AuditDecision,
    /** Backend that ran the call, once capabilities have several (M4). */
    val backend: String?,
    val durationMs: Long,
    val outcome: AuditOutcome,
    val errorCode: String?,
)
