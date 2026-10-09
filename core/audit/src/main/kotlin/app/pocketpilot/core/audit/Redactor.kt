package app.pocketpilot.core.audit

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest

/**
 * Replaces sensitive argument values (text typed, clipboard, file contents, secrets) with their length
 * and a SHA-256 prefix, so the audit log shows that something was sent without storing it.
 */
class Redactor(
    private val sensitiveKeys: Set<String> = DEFAULT_SENSITIVE_KEYS,
) {
    fun redact(arguments: JsonObject): JsonObject = JsonObject(arguments.mapValues { (key, value) -> redactValue(key, value) })

    private fun redactValue(
        key: String,
        value: JsonElement,
    ): JsonElement =
        when {
            key.lowercase() in sensitiveKeys -> summary(value.toString())
            value is JsonObject -> redact(value)
            value is JsonArray -> JsonArray(value.map { redactValue(key, it) })
            else -> value
        }

    private fun summary(raw: String): JsonObject =
        buildJsonObject {
            put("redacted", true)
            put("length", raw.length)
            put("sha256", sha256(raw).take(HASH_PREFIX))
        }

    private fun sha256(raw: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }

    companion object {
        private const val HASH_PREFIX = 16

        val DEFAULT_SENSITIVE_KEYS: Set<String> =
            setOf("text", "clipboard", "content", "contents", "password", "secret", "token", "reply")

        /** True when [value] is a summary produced by [redact]. */
        fun isRedacted(value: JsonElement): Boolean = (value as? JsonObject)?.get("redacted") == JsonPrimitive(true)
    }
}
