package app.pocketpilot.agent.runtime

import app.pocketpilot.core.model.Availability
import app.pocketpilot.core.model.Flavor
import app.pocketpilot.core.model.Phase
import app.pocketpilot.core.model.RiskTier
import app.pocketpilot.core.model.Scope
import app.pocketpilot.core.model.ToolSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    private fun spec(name: String) =
        ToolSpec(
            name = name,
            title = "Title of $name",
            description = "test $name",
            inputSchema =
                Json
                    .parseToJsonElement(
                        """{"type":"object","properties":{"a":{"type":"string"},"b":{"type":"integer"}},"required":["a"]}""",
                    ).jsonObject,
            riskTier = RiskTier.READ,
            requiredScopes = setOf(Scope.DEVICE_READ),
            availability = Availability(Phase.P0, Flavor.entries.toSet()),
        )

    @Test
    fun `sends core tools in full and lists the rest until they are loaded or used`() {
        val others = (1..8).map { spec("extra.tool$it") }
        val tools = AgentTools(listOf(spec("ui.tap"), spec("screen.snapshot")) + others)
        assertEquals(listOf("ui_tap", "screen_snapshot", AgentTools.LOAD), tools.defs.map { it.name })
        assertTrue(tools.catalog.lines().first() == "- extra.tool1(a, b?): Title of extra.tool1")
        assertEquals(8, tools.catalog.lines().size)

        assertEquals(
            "Loaded extra.tool2; their definitions are in your tools now. No tool named nope.",
            tools.load(listOf("extra.tool2", "nope")),
        )
        tools.markUsed(tools.resolve("extra_tool5")!!)
        assertEquals(listOf("ui_tap", "screen_snapshot", "extra_tool2", "extra_tool5", AgentTools.LOAD), tools.defs.map { it.name })
        // The catalog stays the same so the system prompt can be cached.
        assertEquals(8, tools.catalog.lines().size)

        tools.load(others.map { it.name })
        assertTrue(tools.defs.none { it.name == AgentTools.LOAD })
    }

    @Test
    fun `sends everything when only a few tools are left out`() {
        val tools = AgentTools(listOf(spec("ui.tap"), spec("extra.one"), spec("extra.two")))
        assertEquals(listOf("ui_tap", "extra_one", "extra_two"), tools.defs.map { it.name })
        assertEquals("", tools.catalog)
    }
}
