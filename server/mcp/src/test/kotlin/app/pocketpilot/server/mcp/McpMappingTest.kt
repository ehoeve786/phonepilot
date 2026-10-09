package app.pocketpilot.server.mcp

import app.pocketpilot.core.model.Availability
import app.pocketpilot.core.model.Phase
import app.pocketpilot.core.model.RiskTier
import app.pocketpilot.core.model.Scope
import app.pocketpilot.core.model.ToolAnnotations
import app.pocketpilot.core.model.ToolErrorCode
import app.pocketpilot.core.model.ToolResult
import app.pocketpilot.core.model.ToolSpec
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class McpMappingTest {
    @Test
    fun `tool specs keep name, schema and annotations`() {
        val spec =
            ToolSpec(
                name = "ui.tap",
                title = "Tap",
                description = "Taps",
                inputSchema =
                    Json
                        .parseToJsonElement("""{"type":"object","properties":{"element":{"type":"string"}},"required":["element"]}""")
                        .jsonObject,
                riskTier = RiskTier.INTERACT,
                requiredScopes = setOf(Scope.UI_INTERACT),
                annotations = ToolAnnotations(idempotent = false),
                availability = Availability(Phase.P0),
            )
        val tool = spec.toMcpTool()
        assertEquals("ui.tap", tool.name)
        assertEquals(listOf("element"), tool.inputSchema.required)
        assertEquals(false, tool.annotations?.readOnlyHint)
    }

    @Test
    fun `errors carry code and hint in structured content`() {
        val result = ToolResult.error(ToolErrorCode.DEVICE_BUSY, "Another client holds the phone", "Retry in 30 s").toMcpResult()
        assertEquals(true, result.isError)
        assertEquals("DEVICE_BUSY", result.structuredContent!!["code"]!!.jsonPrimitive.content)
        assertEquals("Retry in 30 s", result.structuredContent!!["recoveryHint"]!!.jsonPrimitive.content)
        val text = (result.content.single() as TextContent).text
        assertTrue(text.startsWith("Another client holds the phone"))
        assertTrue(text.contains("Error code: DEVICE_BUSY"))
        assertTrue(text.contains("What to do: Retry in 30 s"))
    }
}
