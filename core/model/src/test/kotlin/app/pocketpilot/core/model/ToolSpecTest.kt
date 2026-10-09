package app.pocketpilot.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolSpecTest {
    private fun spec(name: String) =
        ToolSpec(
            name = name,
            title = "Tap",
            description = "Taps an element",
            inputSchema = JsonObject(emptyMap()),
            riskTier = RiskTier.INTERACT,
            requiredScopes = setOf(Scope.UI_INTERACT),
            requiredCapabilities = setOf(CapabilityId.INJECT_INPUT),
            availability = Availability(Phase.P0),
        )

    @Test
    fun `accepts namespace dot verb names`() {
        assertTrue(ToolSpec.isValidName("ui.tap"))
        assertTrue(ToolSpec.isValidName("screen.wait_for_change"))
        assertTrue(ToolSpec.isValidName("mount.github.create_issue"))
    }

    @Test
    fun `rejects names without a namespace or with bad characters`() {
        assertFalse(ToolSpec.isValidName("tap"))
        assertFalse(ToolSpec.isValidName("UI.tap"))
        assertFalse(ToolSpec.isValidName("ui.tap-now"))
        assertFailsWith<IllegalArgumentException> { spec("tap") }
    }

    @Test
    fun `round-trips through JSON`() {
        val original = spec("ui.tap")
        val decoded = Json.decodeFromString<ToolSpec>(Json.encodeToString(original))
        assertEquals(original, decoded)
        assertEquals("ui", decoded.namespace)
    }

    @Test
    fun `risk tiers are ordered least to most dangerous`() {
        assertTrue(RiskTier.READ < RiskTier.INTERACT)
        assertTrue(RiskTier.SENSITIVE < RiskTier.DESTRUCTIVE)
    }

    @Test
    fun `play profile drops shell exec`() {
        assertFalse(PolicyProfile.PLAY.shellExecAvailable)
        assertTrue(PolicyProfile.OSS.shellExecAvailable)
        assertTrue(PolicyProfile.PRO.shellExecAvailable)
    }
}
