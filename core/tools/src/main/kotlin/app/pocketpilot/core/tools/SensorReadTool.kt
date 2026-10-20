package app.pocketpilot.core.tools

import app.pocketpilot.capability.api.SensorReader
import app.pocketpilot.core.model.Availability
import app.pocketpilot.core.model.CapabilityId
import app.pocketpilot.core.model.Phase
import app.pocketpilot.core.model.RiskTier
import app.pocketpilot.core.model.Scope
import app.pocketpilot.core.model.Session
import app.pocketpilot.core.model.ToolAnnotations
import app.pocketpilot.core.model.ToolCall
import app.pocketpilot.core.model.ToolErrorCode
import app.pocketpilot.core.model.ToolException
import app.pocketpilot.core.model.ToolResult
import app.pocketpilot.core.model.ToolSpec
import app.pocketpilot.core.orchestrator.ToolHandler

/** `sensor.read`: one reading from the light, pressure, temperature or humidity sensor. */
class SensorReadTool(
    private val reader: SensorReader,
) : ToolHandler {
    override val spec: ToolSpec =
        ToolSpec(
            name = "sensor.read",
            title = "Read a sensor",
            description = "Reads one value from a sensor: light (lux), pressure (hPa), temperature (°C) or humidity (%). " +
                "Returns null when the phone has no such sensor.",
            inputSchema =
                schema(
                    """{"type":"object","properties":{"sensor":{"type":"string","enum":["light","pressure","temperature","humidity"]}},
                    "required":["sensor"],"additionalProperties":false}""",
                ),
            riskTier = RiskTier.READ,
            requiredScopes = setOf(Scope.DEVICE_READ),
            annotations = ToolAnnotations(readOnly = true, idempotent = true),
            availability = Availability(Phase.P0),
        )

    override suspend fun execute(
        call: ToolCall,
        session: Session,
    ): ToolResult {
        val name = call.arguments.string("sensor").orEmpty()
        if (name !in SensorReader.SENSORS) invalid("sensor must be one of ${SensorReader.SENSORS.joinToString()}")
        val value = reader.read(name) ?: unavailable(name)
        return ToolResult.success(text = "$name: ${"%.1f".format(value)}")
    }

    private fun unavailable(name: String): Nothing =
        throw ToolException(ToolErrorCode.CAPABILITY_UNAVAILABLE, "No $name reading from the phone", "Try another sensor")
}
