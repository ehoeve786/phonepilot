package app.pocketpilot.core.orchestrator

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ArgumentValidatorTest {
    private val schema =
        Json
            .parseToJsonElement(
                """{"type":"object","properties":{"path":{"type":"string"},"append":{"type":"boolean"},
                "count":{"type":"integer"},"level":{"type":"number"}},"additionalProperties":false}""",
            ).jsonObject

    @Test
    fun `text spelling a boolean or number becomes that type`() {
        val sent =
            buildJsonObject {
                put("path", "true")
                put("append", "True")
                put("count", "5")
                put("level", "0.3")
            }
        val coerced = ArgumentValidator.coerce(schema, sent)
        assertEquals(JsonPrimitive("true"), coerced["path"])
        assertEquals(JsonPrimitive(true), coerced["append"])
        assertEquals(JsonPrimitive(5L), coerced["count"])
        assertEquals(JsonPrimitive(0.3), coerced["level"])
        assertNull(ArgumentValidator.validate(schema, coerced))
    }

    @Test
    fun `other text is left for validation to refuse`() {
        val sent = buildJsonObject { put("append", "yes") }
        val coerced = ArgumentValidator.coerce(schema, sent)
        assertEquals(sent, coerced)
        assertEquals("Argument 'append' must be boolean", ArgumentValidator.validate(schema, coerced))
    }

    @Test
    fun `JSON written as text becomes objects and arrays, inside items too`() {
        val nested =
            Json
                .parseToJsonElement(
                    """{"type":"object","properties":{"triggers":{"type":"array","items":{"type":"object",
                    "properties":{"type":{"type":"string"},"params":{"type":"object","additionalProperties":{"type":"string"}}}}},
                    "steps":{"type":"array","items":{"type":"object"}}}}""",
                ).jsonObject
        val sent =
            Json
                .parseToJsonElement(
                    """{"triggers":[{"type":"time.daily","params":"{\"time\":\"07:00\"}"}],"steps":["{\"type\":\"notify.post\"}"]}""",
                ).jsonObject
        val expected =
            Json
                .parseToJsonElement(
                    """{"triggers":[{"type":"time.daily","params":{"time":"07:00"}}],"steps":[{"type":"notify.post"}]}""",
                ).jsonObject
        assertEquals(expected, ArgumentValidator.coerce(nested, sent))
    }
}
