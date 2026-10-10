package app.pocketpilot.agent.providers.gemini

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class GeminiSchemaTest {
    private fun sanitize(schema: String) = GeminiSchema.sanitize(Json.parseToJsonElement(schema).jsonObject)

    @Test
    fun `drops unsupported keywords at every depth`() {
        val schema =
            """
            {"${'$'}schema":"https://json-schema.org/draft/2020-12/schema","type":"object","additionalProperties":false,
             "${'$'}defs":{"p":{"type":"string"}},"description":"Args",
             "properties":{
               "query":{"type":"string","default":"x","examples":["a"],"format":"uri","minLength":1},
               "limit":{"type":"integer","minimum":1,"maximum":50,"format":"int32"},
               "tags":{"type":"array","items":{"type":"string","enum":["a","b"],"title":"Tag"},"minItems":1,"uniqueItems":true},
               "filter":{"type":"object","additionalProperties":false,"properties":{"since":{"type":"string","format":"date-time"}}}},
             "required":["query","missing"]}
            """.trimIndent()

        val expected =
            """
            {"type":"object","description":"Args",
             "properties":{
               "query":{"type":"string"},
               "limit":{"type":"integer","format":"int32","minimum":1,"maximum":50},
               "tags":{"type":"array","minItems":1,"items":{"type":"string","enum":["a","b"]}},
               "filter":{"type":"object","properties":{"since":{"type":"string","format":"date-time"}}}},
             "required":["query"]}
            """.trimIndent()
        assertEquals(Json.parseToJsonElement(expected), sanitize(schema))
    }

    @Test
    fun `keeps a property named like a keyword`() {
        val sanitized = sanitize("""{"type":"object","properties":{"default":{"type":"string"}},"required":["default"]}""")

        assertEquals(
            Json.parseToJsonElement("""{"type":"object","properties":{"default":{"type":"string"}},"required":["default"]}"""),
            sanitized,
        )
    }

    @Test
    fun `turns type lists into nullable and string consts into enums`() {
        val sanitized = sanitize("""{"anyOf":[{"type":["string","null"]},{"const":"auto"},{"type":"integer","enum":[1,2]}]}""")

        val expected = """{"anyOf":[{"type":"string","nullable":true},{"enum":["auto"]},{"type":"integer"}]}"""
        assertEquals(Json.parseToJsonElement(expected), sanitized)
    }
}
