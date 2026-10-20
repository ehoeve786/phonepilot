package app.pocketpilot.core.tools

import app.pocketpilot.capability.api.AudioControl
import app.pocketpilot.core.model.Availability
import app.pocketpilot.core.model.Phase
import app.pocketpilot.core.model.RiskTier
import app.pocketpilot.core.model.Scope
import app.pocketpilot.core.model.Session
import app.pocketpilot.core.model.ToolCall
import app.pocketpilot.core.model.ToolResult
import app.pocketpilot.core.model.ToolSpec
import app.pocketpilot.core.orchestrator.ToolHandler

/** `audio.control`: mutes or unmutes the microphone, turns the speakerphone on or off, or raises or lowers the media volume. */
class AudioControlTool(
    private val audio: AudioControl,
) : ToolHandler {
    override val spec: ToolSpec =
        ToolSpec(
            name = "audio.control",
            title = "Control audio",
            description =
                "Mutes or unmutes the microphone, turns the speakerphone on or off, or raises or lowers the media volume one step.",
            inputSchema =
                schema(
                    """{"type":"object","properties":{"action":{"type":"string","enum":
                    ["mic_mute","mic_unmute","speaker_on","speaker_off","volume_up","volume_down"]}},
                    "required":["action"],"additionalProperties":false}""",
                ),
            riskTier = RiskTier.INTERACT,
            requiredScopes = setOf(Scope.SETTINGS_WRITE),
            availability = Availability(Phase.P0),
        )

    override suspend fun execute(
        call: ToolCall,
        session: Session,
    ): ToolResult {
        when (val action = call.arguments.string("action").orEmpty()) {
            "mic_mute" -> audio.setMicrophoneMuted(true)
            "mic_unmute" -> audio.setMicrophoneMuted(false)
            "speaker_on" -> audio.setSpeakerphone(true)
            "speaker_off" -> audio.setSpeakerphone(false)
            "volume_up" -> audio.adjustMediaVolume(up = true)
            "volume_down" -> audio.adjustMediaVolume(up = false)
            else -> invalid("unknown audio action '$action'")
        }
        return ToolResult.success(text = "Done: ${call.arguments.string("action")}")
    }
}
