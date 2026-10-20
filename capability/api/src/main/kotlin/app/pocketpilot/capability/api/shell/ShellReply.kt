package app.pocketpilot.capability.api.shell

/**
 * The reply the privileged service sends for a command: the exit code, then the length of stdout, then stdout
 * and stderr. Lengths let output that holds NUL characters be read whole, which a NUL-separated reply could not.
 */
object ShellReply {
    fun encode(
        exitCode: Int,
        stdout: String,
        stderr: String,
    ): String = "$exitCode\n${stdout.length}\n$stdout$stderr"

    /** An unreadable reply is reported as a failed command, not as an empty success. */
    fun decode(reply: String): ShellResult {
        val exitEnd = reply.indexOf('\n')
        val lengthEnd = if (exitEnd < 0) -1 else reply.indexOf('\n', exitEnd + 1)
        val exit = if (exitEnd < 0) null else reply.substring(0, exitEnd).toIntOrNull()
        val length = if (lengthEnd < 0) null else reply.substring(exitEnd + 1, lengthEnd).toIntOrNull()
        if (exit == null || length == null || length < 0 || lengthEnd + 1 + length > reply.length) {
            return ShellResult(exitCode = -1, stdout = "", stderr = "The shell reply could not be read")
        }
        val start = lengthEnd + 1
        return ShellResult(
            exitCode = exit,
            stdout = reply.substring(start, start + length),
            stderr = reply.substring(start + length),
            timedOut = exit == -1,
        )
    }
}
