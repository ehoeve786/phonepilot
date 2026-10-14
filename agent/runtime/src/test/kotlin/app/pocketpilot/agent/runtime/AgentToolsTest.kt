package app.pocketpilot.agent.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class AgentToolsTest {
    @Test
    fun `every array gets an items schema, however deep`() {
        val schema =
            Json
                .parseToJsonElement(
                    """{"type":"object","properties":{"a":{"type":"array"},"b":{"type":"array","items":{"type":"object",
                    "properties":{"c":{"type":"array"}}}},"d":{"type":"array","items":{"type":"string"}}}}""",
                ).jsonObject
        val expected =
            Json.parseToJsonElement(
                """{"type":"object","properties":{"a":{"type":"array","items":{}},"b":{"type":"array","items":{"type":"object",
                "properties":{"c":{"type":"array","items":{}}}}},"d":{"type":"array","items":{"type":"string"}}}}""",
            )
        assertEquals(expected, AgentTools.portable(schema))
    }
}
