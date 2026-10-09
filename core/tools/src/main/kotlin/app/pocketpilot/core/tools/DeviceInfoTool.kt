package app.pocketpilot.core.tools

import app.pocketpilot.capability.api.DeviceInfo
import app.pocketpilot.capability.api.DeviceInfoSource
import app.pocketpilot.core.model.Availability
import app.pocketpilot.core.model.Phase
import app.pocketpilot.core.model.RiskTier
import app.pocketpilot.core.model.Scope
import app.pocketpilot.core.model.Session
import app.pocketpilot.core.model.ToolAnnotations
import app.pocketpilot.core.model.ToolCall
import app.pocketpilot.core.model.ToolResult
import app.pocketpilot.core.model.ToolSpec
import app.pocketpilot.core.orchestrator.ToolHandler
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** `device.info`: model, Android version, screen, battery and network (spec section 5). */
class DeviceInfoTool(
    private val source: DeviceInfoSource,
) : ToolHandler {
    override val spec: ToolSpec =
        ToolSpec(
            name = "device.info",
            title = "Device info",
            description =
                "Returns the phone's manufacturer, model, Android version, screen size in pixels, " +
                    "battery level and charging state, and network connection. Takes no arguments.",
            inputSchema = Json.parseToJsonElement("""{"type":"object","properties":{},"additionalProperties":false}""").jsonObject,
            riskTier = RiskTier.READ,
            requiredScopes = setOf(Scope.DEVICE_READ),
            annotations = ToolAnnotations(readOnly = true, idempotent = true),
            availability = Availability(Phase.P0),
        )

    override suspend fun execute(
        call: ToolCall,
        session: Session,
    ): ToolResult {
        val info = source.read()
        val structured = json.encodeToJsonElement(DeviceInfo.serializer(), info) as JsonObject
        return ToolResult.success(text = summary(info), structured = structured)
    }

    private fun summary(info: DeviceInfo): String =
        buildString {
            append("${info.manufacturer} ${info.model}, Android ${info.androidVersion} (API ${info.sdkInt}). ")
            append("Screen ${info.screen.widthPx}x${info.screen.heightPx} px at ${info.screen.densityDpi} dpi. ")
            val level = info.battery.levelPercent?.let { "$it%" } ?: "unknown"
            append("Battery $level${if (info.battery.charging) ", charging" else ""}. ")
            append(if (info.network.connected) "Online via ${info.network.transport}." else "Offline.")
        }

    private companion object {
        val json = Json { encodeDefaults = true }
    }
}
