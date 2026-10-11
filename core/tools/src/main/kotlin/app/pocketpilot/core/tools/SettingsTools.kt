package app.pocketpilot.core.tools

import app.pocketpilot.capability.api.settings.SettingKey
import app.pocketpilot.capability.api.settings.SettingsController
import app.pocketpilot.core.model.Availability
import app.pocketpilot.core.model.CapabilityId
import app.pocketpilot.core.model.Phase
import app.pocketpilot.core.model.RiskTier
import app.pocketpilot.core.model.Scope
import app.pocketpilot.core.model.Session
import app.pocketpilot.core.model.ToolAnnotations
import app.pocketpilot.core.model.ToolCall
import app.pocketpilot.core.model.ToolResult
import app.pocketpilot.core.model.ToolSpec
import app.pocketpilot.core.orchestrator.ToolHandler
import kotlinx.serialization.json.JsonObject

private val KEYS = SettingKey.entries.joinToString(",") { "\"${it.wire}\"" }
private val KEY_LIST = SettingKey.entries.joinToString("; ") { "${it.wire}: ${it.values}" }

/** `settings.get`: one allowlisted device setting. */
class SettingsGetTool(
    private val settings: SettingsController,
) : ToolHandler {
    override val spec =
        ToolSpec(
            name = "settings.get",
            title = "Read a setting",
            description = "Reads a device setting directly, without opening the Settings app. Keys and values: $KEY_LIST.",
            inputSchema =
                schema(
                    """{"type":"object","properties":{"key":{"type":"string","enum":[$KEYS]}},
                    "required":["key"],"additionalProperties":false}""",
                ),
            riskTier = RiskTier.READ,
            requiredScopes = setOf(Scope.DEVICE_READ),
            requiredCapabilities = setOf(CapabilityId.WRITE_SETTINGS),
            annotations = ToolAnnotations(readOnly = true, idempotent = true),
            availability = Availability(Phase.P0),
        )

    override suspend fun execute(
        call: ToolCall,
        session: Session,
    ): ToolResult {
        val key = call.arguments.settingKey()
        return ToolResult.success("${key.wire}: ${settings.get(key)}")
    }
}

/** `settings.set`: changes one allowlisted setting and reads it back. */
class SettingsSetTool(
    private val settings: SettingsController,
) : ToolHandler {
    override val spec =
        ToolSpec(
            name = "settings.set",
            title = "Change a setting",
            description =
                "Changes a device setting directly, much faster and more reliable than tapping through the Settings app; " +
                    "use it whenever the setting is listed. Returns the value read back afterwards. Keys and values: $KEY_LIST.",
            inputSchema =
                schema(
                    """{"type":"object","properties":{"key":{"type":"string","enum":[$KEYS]},
                    "value":{"type":"string"}},"required":["key","value"],"additionalProperties":false}""",
                ),
            riskTier = RiskTier.SENSITIVE,
            requiredScopes = setOf(Scope.SETTINGS_WRITE),
            requiredCapabilities = setOf(CapabilityId.WRITE_SETTINGS),
            annotations = ToolAnnotations(idempotent = true),
            availability = Availability(Phase.P0),
        )

    override suspend fun execute(
        call: ToolCall,
        session: Session,
    ): ToolResult {
        val key = call.arguments.settingKey()
        val raw = call.arguments.string("value") ?: invalid("Give value: ${key.values}")
        val value = key.normalize(raw) ?: invalid("${key.wire} takes ${key.values}, not '$raw'")
        settings.set(key, value)
        return ToolResult.success("${key.wire} is now ${settings.get(key)}")
    }
}

private fun JsonObject.settingKey(): SettingKey {
    val wire = string("key") ?: invalid("Give key: one of ${SettingKey.entries.joinToString { it.wire }}")
    return SettingKey.fromWire(wire) ?: invalid("Unknown setting '$wire'; allowed: ${SettingKey.entries.joinToString { it.wire }}")
}
