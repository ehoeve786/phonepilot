package app.pocketpilot.capability.api.shell

import app.pocketpilot.capability.api.CapabilityBackend
import kotlinx.serialization.Serializable

/** RUN_SHELL: one `sh -c` command as the adb shell user (spec section 5, `shell.exec`). */
interface ShellRunner : CapabilityBackend {
    suspend fun run(
        command: String,
        timeoutMs: Long,
    ): ShellResult
}

@Serializable
data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    /** True when the command outlived its timeout and was killed. */
    val timedOut: Boolean = false,
)
