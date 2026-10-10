package app.pocketpilot.agent.providers.gemini

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

/**
 * Reduces a JSON Schema to the OpenAPI subset Gemini accepts for function parameters. Gemini rejects
 * the whole request when a schema carries a keyword it does not know, so anything outside that subset
 * is dropped rather than passed through.
 */
internal object GeminiSchema {
    private val NUMBER_KEYWORDS = setOf("minimum", "maximum", "minItems", "maxItems")

    /** Formats Gemini accepts; others, such as `uri`, are rejected. */
    private val FORMATS = setOf("enum", "date-time", "float", "double", "int32", "int64")

    fun sanitize(schema: JsonObject): JsonObject =
        buildJsonObject {
            when (val type = schema["type"]) {
                is JsonPrimitive -> {
                    put("type", type)
                }

                // A JSON Schema type list such as ["string", "null"] becomes one type plus `nullable`.
                is JsonArray -> {
                    val names = type.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    names.firstOrNull { it != "null" }?.let { put("type", JsonPrimitive(it)) }
                    if ("null" in names) put("nullable", JsonPrimitive(true))
                }

                else -> {}
            }
            schema.string("description")?.let { put("description", JsonPrimitive(it)) }
            schema.string("format")?.takeIf { it in FORMATS }?.let { put("format", JsonPrimitive(it)) }
            (schema["nullable"] as? JsonPrimitive)?.let { put("nullable", it) }
            for (key in NUMBER_KEYWORDS) {
                (schema[key] as? JsonPrimitive)?.takeIf { !it.isString }?.let { put(key, it) }
            }
            stringEnum(schema)?.let { put("enum", it) }

            val properties =
                (schema["properties"] as? JsonObject)
                    ?.mapNotNull { (name, value) -> (value as? JsonObject)?.let { name to sanitize(it) } }
                    ?.toMap()
                    .orEmpty()
            if (properties.isNotEmpty()) put("properties", JsonObject(properties))
            (schema["required"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?.filter { it in properties }
                ?.takeIf { it.isNotEmpty() }
                ?.let { names -> put("required", JsonArray(names.map(::JsonPrimitive))) }
            (schema["items"] as? JsonObject)?.let { put("items", sanitize(it)) }
            (schema["anyOf"] as? JsonArray)
                ?.mapNotNull { (it as? JsonObject)?.let(::sanitize) }
                ?.takeIf { it.isNotEmpty() }
                ?.let { put("anyOf", JsonArray(it)) }
        }

    /** Gemini enums hold strings only; a string `const` becomes a one-value enum. */
    private fun stringEnum(schema: JsonObject): JsonArray? {
        val values: List<JsonElement> =
            (schema["enum"] as? JsonArray)
                ?: (schema["const"] as? JsonPrimitive)?.let { listOf(it) }
                ?: return null
        return if (values.isNotEmpty() && values.all { it is JsonPrimitive && it.isString }) JsonArray(values) else null
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}
