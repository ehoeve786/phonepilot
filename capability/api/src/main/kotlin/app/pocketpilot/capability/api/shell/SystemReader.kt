package app.pocketpilot.capability.api.shell

import app.pocketpilot.capability.api.CapabilityBackend

/** Runs read-only adb commands that [AdbQuery] allows, as an argv list without a shell. */
interface SystemReader : CapabilityBackend {
    /**
     * Runs [argv]. When [filter] is not empty, only output lines containing it (ignoring case) are kept,
     * before the output is cut to the service's size limit.
     */
    suspend fun read(
        argv: List<String>,
        filter: String,
        timeoutMs: Long,
    ): ShellResult
}
