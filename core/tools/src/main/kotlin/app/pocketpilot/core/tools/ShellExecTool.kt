package app.pocketpilot.core.tools

import app.pocketpilot.capability.api.shell.ShellResult
import app.pocketpilot.capability.api.shell.ShellRunner
import app.pocketpilot.core.model.Availability
import app.pocketpilot.core.model.CapabilityId
import app.pocketpilot.core.model.ContentPart
import app.pocketpilot.core.model.Flavor
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
import kotlinx.serialization.json.JsonObject

/**
 * `shell.exec` (spec section 5): one command as the adb shell user. Off until the owner turns it on
 * ([enabled]), confirmed on every call with the exact command, and left out of the Play build.
 */
class ShellExecTool(
    private val shell: ShellRunner,
    private val enabled: () -> Boolean,
) : ToolHandler {
    override val spec =
        ToolSpec(
            name = "shell.exec",
            title = "Run shell command",
            description =
                "Runs one command with sh -c as the adb shell user (through Shizuku), for what no other tool covers: " +
                    "cmd, dumpsys, pm, appops, settings, am and so on. The owner approves every command. Prefer the " +
                    "dedicated tools (settings.set, app.set_permission, app.set_appop) when they fit.",
            inputSchema =
                schema(
                    """{"type":"object","properties":{"command":{"type":"string"},"timeoutMs":{"type":"integer",
                    "minimum":1000,"maximum":120000,"description":"Default 30000"}},"required":["command"],
                    "additionalProperties":false}""",
                ),
            riskTier = RiskTier.DESTRUCTIVE,
            requiredScopes = setOf(Scope.SHELL_EXEC),
            requiredCapabilities = setOf(CapabilityId.RUN_SHELL),
            annotations = ToolAnnotations(destructive = true, openWorld = true),
            availability = Availability(Phase.P0, setOf(Flavor.OSS, Flavor.PRO)),
        )

    override val timeoutMs: Long = MAX_TIMEOUT_MS + 5_000

    override fun confirmationDetail(call: ToolCall): String? = call.arguments.string("command")

    override suspend fun execute(
        call: ToolCall,
        session: Session,
    ): ToolResult {
        if (!enabled()) {
            throw ToolException(
                ToolErrorCode.CAPABILITY_UNAVAILABLE,
                "Shell commands are off on this phone",
                "Use the other tools, or ask the owner to turn on Shell commands in PocketPilot",
            )
        }
        val command = call.arguments.string("command")?.takeIf(String::isNotBlank) ?: invalid("Give command")
        val timeout = (call.arguments.long("timeoutMs") ?: DEFAULT_TIMEOUT_MS).coerceIn(1_000, MAX_TIMEOUT_MS)
        val result = shell.run(command, timeout)
        val text =
            buildString {
                append(if (result.timedOut) "Timed out after $timeout ms" else "Exit code ${result.exitCode}")
                if (result.stdout.isNotEmpty()) append("\nstdout:\n").append(result.stdout.trimEnd())
                if (result.stderr.isNotEmpty()) append("\nstderr:\n").append(result.stderr.trimEnd())
            }
        return ToolResult(
            content = listOf(ContentPart.Text(text)),
            structured = compactJson.encodeToJsonElement(ShellResult.serializer(), result) as? JsonObject,
            isError = result.timedOut || result.exitCode != 0,
        )
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000L
        const val MAX_TIMEOUT_MS = 120_000L
    }
}
