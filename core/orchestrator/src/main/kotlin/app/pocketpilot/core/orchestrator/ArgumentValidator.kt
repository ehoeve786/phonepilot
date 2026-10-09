package app.pocketpilot.core.orchestrator

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
