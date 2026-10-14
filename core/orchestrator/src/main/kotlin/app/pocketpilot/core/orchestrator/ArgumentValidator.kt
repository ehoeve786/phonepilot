package app.pocketpilot.core.orchestrator

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Checks call arguments against the subset of JSON Schema that tool schemas use: top-level `required`,
 * `additionalProperties: false`, and each property's `type` and `enum`. Returns the first problem, or
 * null when the arguments are acceptable.
 */
object ArgumentValidator {
    fun validate(
        schema: JsonObject,
        arguments: JsonObject,
    ): String? {
        val properties = schema["properties"]?.jsonObject ?: JsonObject(emptyMap())
        val required = schema["required"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()

        required.firstOrNull { it !in arguments }?.let { return "Missing required argument '$it'" }

        if (schema["additionalProperties"] == JsonPrimitive(false)) {
            arguments.keys.firstOrNull { it !in properties }?.let { return "Unknown argument '$it'" }
        }

        for ((name, value) in arguments) {
            val propertySchema = properties[name] as? JsonObject ?: continue
            propertyProblem(name, propertySchema, value)?.let { return it }
        }
        return null
    }

    /**
     * Turns text that spells a value of a property's declared type into that type: "true" and "false"
     * for booleans, digits for integers and numbers, and JSON text for objects and arrays, as models
     * whose providers cannot describe free-form objects send them. Works through nested properties and
     * array items. Everything else is left for [validate] to judge.
     */
    fun coerce(
        schema: JsonObject,
        arguments: JsonObject,
    ): JsonObject = coerceValue(schema, arguments) as? JsonObject ?: arguments

    private fun coerceValue(
        schema: JsonObject,
        value: JsonElement,
    ): JsonElement {
        val type = (schema["type"] as? JsonPrimitive)?.contentOrNull
        val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (text != null) return fromText(type, text)?.let { coerceValue(schema, it) } ?: value
        return when (value) {
            is JsonObject -> {
                val properties = schema["properties"] as? JsonObject
                val extra = schema["additionalProperties"] as? JsonObject
                if (properties == null && extra == null) return value
                val coerced =
                    value.mapValues { (name, child) ->
                        val childSchema = properties?.get(name) as? JsonObject ?: extra
                        childSchema?.let { coerceValue(it, child) } ?: child
                    }
                if (coerced == value) value else JsonObject(coerced)
            }

            is JsonArray -> {
                val items = (schema["items"] as? JsonObject)?.takeIf { it["type"] != null }
                // Items of any type may arrive as JSON text from providers that need every item typed.
                val coerced = value.map { if (items != null) coerceValue(items, it) else anyFromText(it) }
                if (coerced == value) value else JsonArray(coerced)
            }

            else -> {
                value
            }
        }
    }

    /** A string holding a JSON object or array becomes that object or array. */
    private fun anyFromText(value: JsonElement): JsonElement {
        val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim() ?: return value
        if (!(text.startsWith("{") || text.startsWith("["))) return value
        return parse(text)?.takeIf { it is JsonObject || it is JsonArray } ?: value
    }

    /** [text] read as a value of [type], or null when it does not spell one. */
    private fun fromText(
        type: String?,
        text: String,
    ): JsonElement? {
        val t = text.trim()
        return when (type) {
            "boolean" -> {
                t.lowercase().toBooleanStrictOrNull()?.let(::JsonPrimitive)
            }

            "integer" -> {
                t.toLongOrNull()?.let(::JsonPrimitive)
            }

            "number" -> {
                t.toLongOrNull()?.let(::JsonPrimitive) ?: t.toDoubleOrNull()?.takeIf(Double::isFinite)?.let(::JsonPrimitive)
            }

            "object" -> {
                parse(t) as? JsonObject
            }

            "array" -> {
                parse(t) as? JsonArray
            }

            else -> {
                null
            }
        }
    }

    private fun parse(text: String): JsonElement? =
        try {
            Json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            null
        }

    private fun propertyProblem(
        name: String,
        schema: JsonObject,
        value: JsonElement,
    ): String? {
        val types =
            when (val type = schema["type"]) {
                null -> emptyList()
                is JsonArray -> type.map { it.jsonPrimitive.content }
                else -> listOf(type.jsonPrimitive.content)
            }
        if (types.isNotEmpty() && types.none { matches(it, value) }) {
            return "Argument '$name' must be ${types.joinToString(" or ")}"
        }
        val allowed = schema["enum"]?.jsonArray
        if (allowed != null && value !in allowed) {
            return "Argument '$name' must be one of ${allowed.joinToString { it.toString() }}"
        }
        return null
    }

    private fun matches(
        type: String,
        value: JsonElement,
    ): Boolean =
        when (type) {
            "object" -> value is JsonObject
            "array" -> value is JsonArray
            "null" -> value is JsonNull
            "string" -> value is JsonPrimitive && value.isString
            "boolean" -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
            "integer" -> value is JsonPrimitive && !value.isString && value.longOrNull != null
            "number" -> value is JsonPrimitive && !value.isString && value.contentOrNull != null && value.doubleOrNull != null
            else -> true
        }
}
