package app.pocketpilot.core.tools

import app.pocketpilot.capability.api.apps.AppAdminArgs
import app.pocketpilot.capability.api.shell.AdbQuery
import app.pocketpilot.capability.api.shell.ShellResult
import app.pocketpilot.capability.api.shell.SystemReader
import app.pocketpilot.core.model.Availability
import app.pocketpilot.core.model.CapabilityId
import app.pocketpilot.core.model.ContentPart
import app.pocketpilot.core.model.Phase
import app.pocketpilot.core.model.RiskTier
import app.pocketpilot.core.model.Scope
import app.pocketpilot.core.model.Session
import app.pocketpilot.core.model.ToolAnnotations
import app.pocketpilot.core.model.ToolCall
import app.pocketpilot.core.model.ToolResult
import app.pocketpilot.core.model.ToolSpec
import app.pocketpilot.core.orchestrator.ToolHandler

private const val OUTPUT_PROPS =
    """"grep":{"type":"string","description":"Keep only output lines containing this text (ignoring case); saves tokens"},
    "maxChars":{"type":"integer","minimum":200,"maximum":60000,"description":"Default 6000"}"""

private fun readSpec(
    name: String,
    title: String,
    description: String,
    properties: String,
    required: String,
    tier: RiskTier,
) = ToolSpec(
    name = name,
    title = title,
    description = description,
    inputSchema = schema("""{"type":"object","properties":{$properties},"required":[$required],"additionalProperties":false}"""),
    riskTier = tier,
    requiredScopes = setOf(Scope.DEVICE_READ),
    requiredCapabilities = setOf(CapabilityId.RUN_SHELL),
    annotations = ToolAnnotations(readOnly = true),
    availability = Availability(Phase.P0),
)

/** Cuts [result] to [maxChars] and says how to see more. */
private fun output(
    result: ShellResult,
    maxChars: Int,
    redact: Boolean = false,
): ToolResult {
    val stdout = result.stdout.trimEnd().let { if (redact) LogRedactor.redact(it) else it }
    val text =
        buildString {
            if (result.timedOut) append("Timed out; partial output.\n")
            if (result.exitCode != 0 && !result.timedOut) append("Exit code ${result.exitCode}\n")
            // Logs are newest last, so they keep their end.
            if (stdout.length > maxChars && redact) append("[cut: ${stdout.length - maxChars} earlier characters; narrow it with grep]\n")
            if (stdout.isEmpty()) append("(no output)") else append(if (redact) stdout.takeLast(maxChars) else stdout.take(maxChars))
            if (stdout.length > maxChars && !redact) {
                append("\n[cut: ${stdout.length - maxChars} more characters; narrow it with grep or raise maxChars]")
            }
            val stderr = result.stderr.trim()
            if (stderr.isNotEmpty()) append("\nstderr: ").append(stderr.take(ERROR_CHARS))
        }
    return ToolResult(content = listOf(ContentPart.Text(text)), isError = result.exitCode != 0 && stdout.isEmpty())
}

private fun ToolCall.maxChars(): Int = (arguments.int("maxChars") ?: DEFAULT_CHARS).coerceIn(200, 60_000)

private const val DEFAULT_CHARS = 6_000
private const val ERROR_CHARS = 500

/** `adb.query`: any read-only adb shell command, without asking the owner. */
class AdbQueryTool(
    private val reader: SystemReader,
) : ToolHandler {
    override val spec =
        readSpec(
            "adb.query",
            "Read system state",
            "Runs one read-only adb shell command through Shizuku, without opening any app, for troubleshooting and " +
                "anything the other tools do not report. Examples: dumpsys battery, dumpsys batterystats --charged, " +
                "dumpsys meminfo <package>, dumpsys wifi, dumpsys activity activities, dumpsys deviceidle, " +
                "cmd wifi status, cmd package list packages -3, settings get global <key>, getprop ro.build.version.security_patch, " +
                "am get-standby-bucket <package>, appops get <package>, top -b -n 1 -m 10, ps -A, df -h, ip addr, ip route, " +
                "ping -c 3 1.1.1.1, cat /sys/class/power_supply/battery/temp. Allowed programs: " +
                "${AdbQuery.PROGRAMS.joinToString()}; dumpsys -l lists services. Only commands that change nothing run; services " +
                "holding personal data (notifications, accounts, messages, calls, location, clipboard) are refused. " +
                "Words cannot contain spaces or quotes, and there are no pipes: use grep instead. Use shell.exec to change things.",
            """"command":{"type":"string","description":"For example dumpsys battery"},$OUTPUT_PROPS,
            "timeoutMs":{"type":"integer","minimum":1000,"maximum":60000,"description":"Default 15000"}""",
            "\"command\"",
            RiskTier.READ,
        )

    override val timeoutMs: Long = 65_000

    override suspend fun execute(
        call: ToolCall,
        session: Session,
    ): ToolResult {
        val argv = AdbQuery.split(call.arguments.string("command") ?: invalid("Give command"))
        try {
            AdbQuery.check(argv)
        } catch (e: IllegalArgumentException) {
            invalid(e.message ?: "Not a read-only command")
        }
        val timeout = (call.arguments.long("timeoutMs") ?: 15_000L).coerceIn(1_000, 60_000)
        return output(reader.read(argv, call.arguments.string("grep").orEmpty(), timeout), call.maxChars())
    }
}

/** `adb.logs`: recent logcat lines and crashes, for finding out why an app misbehaves. */
class AdbLogsTool(
    private val reader: SystemReader,
) : ToolHandler {
    override val spec =
        readSpec(
            "adb.logs",
            "Read logs",
            "Reads the phone's recent logs (logcat) through Shizuku, newest last, to find out why an app crashes or " +
                "misbehaves. source: logcat (default), crashes (Java crash stack traces) or anr (apps that froze). " +
                "Give package to see one app only. Emails, phone numbers and tokens are masked.",
            """"package":{"type":"string"},"source":{"type":"string","enum":["logcat","crashes","anr"]},
            "level":{"type":"string","enum":["V","D","I","W","E"],"description":"Lowest level to show, default W"},
            "minutes":{"type":"integer","minimum":1,"maximum":1440,"description":"How far back, default 15"},
            "lines":{"type":"integer","minimum":1,"maximum":2000,"description":"At most this many lines, default 150"},
            $OUTPUT_PROPS""",
            "",
            RiskTier.SENSITIVE,
        )

    override suspend fun targetPackage(call: ToolCall): String? = call.arguments.string("package")

    override suspend fun confirmationDetail(call: ToolCall): String =
        "Read ${call.arguments.string("source") ?: "logcat"} logs of ${call.arguments.string("package") ?: "all apps"}"

    override suspend fun execute(
        call: ToolCall,
        session: Session,
    ): ToolResult {
        val pkg = call.arguments.string("package")?.also { if (!AppAdminArgs.PACKAGE.matches(it)) invalid("Not a package name: $it") }
        val lines = (call.arguments.int("lines") ?: 150).coerceIn(1, 2_000)
        val grep = call.arguments.string("grep").orEmpty()
        val result =
            when (call.arguments.string("source") ?: "logcat") {
                "crashes" -> {
                    reader
                        .read(listOf("logcat", "-d", "-b", "crash", "-v", "time", "-t", "$lines"), grep, TIMEOUT_MS)
                        .onlyBlocks(pkg) { "FATAL EXCEPTION" in it || "*** ***" in it }
                }

                "anr" -> {
                    reader
                        .read(listOf("dumpsys", "dropbox", "--print", "data_app_anr"), grep, TIMEOUT_MS)
                        .onlyBlocks(pkg) { it.startsWith("========") }
                }

                "logcat" -> {
                    logcat(pkg, call, lines, grep)
                }

                else -> {
                    invalid("source must be logcat, crashes or anr")
                }
            }
        return output(result, call.maxChars(), redact = true)
    }

    private suspend fun logcat(
        pkg: String?,
        call: ToolCall,
        lines: Int,
        grep: String,
    ): ShellResult {
        val level = call.arguments.string("level") ?: "W"
        if (level !in setOf("V", "D", "I", "W", "E")) invalid("level must be V, D, I, W or E")
        val minutes = (call.arguments.int("minutes") ?: 15).coerceIn(1, 1_440)
        val since = "%.3f".format(java.util.Locale.ROOT, (System.currentTimeMillis() - minutes * 60_000L) / 1000.0)
        val argv = mutableListOf("logcat", "-d", "-v", "time", "-T", since, "*:$level")
        var filter = grep
        if (pkg != null) {
            val pid =
                reader
                    .read(listOf("pidof", pkg), "", TIMEOUT_MS)
                    .stdout
                    .trim()
                    .substringBefore(' ')
            // A running app is filtered by process; otherwise keep the lines that name it.
            if (pid.toIntOrNull() != null) {
                argv += "--pid=$pid"
            } else if (filter.isEmpty()) {
                filter = pkg
            }
        }
        val result = reader.read(argv, filter, TIMEOUT_MS)
        return result.copy(
            stdout =
                result.stdout
                    .lineSequence()
                    .toList()
                    .takeLast(lines + 1)
                    .joinToString("\n"),
        )
    }

    /** Keeps the reports (each starting at a line [starts] accepts) that mention [pkg]. */
    private fun ShellResult.onlyBlocks(
        pkg: String?,
        starts: (String) -> Boolean,
    ): ShellResult {
        if (pkg == null) return this
        val blocks = mutableListOf<MutableList<String>>()
        stdout.lineSequence().forEach { line ->
            if (starts(line) ||
                blocks.isEmpty()
            ) {
                blocks += mutableListOf(line)
            } else {
                blocks.last() += line
            }
        }
        val kept = blocks.filter { block -> block.any { pkg in it } }
        return copy(stdout = kept.joinToString("\n") { it.joinToString("\n") })
    }

    private companion object {
        const val TIMEOUT_MS = 20_000L
    }
}

/** Masks personal data that apps write to their logs. */
internal object LogRedactor {
    private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val PHONE = Regex("\\+\\d{7,15}\\b|\\(?\\b\\d{3}\\)?[ .-]\\d{3}[ .-]\\d{4}\\b")
    private val BEARER =
        Regex("(?i)(bearer|token|authorization|password|passwd|secret|api[_-]?key)([\"']?\\s*[:=]\\s*[\"']?|\\s+)[^\\s\"',;]+")
    private val LONG_SECRET = Regex("\\b[A-Za-z0-9_\\-]{40,}\\b")

    fun redact(text: String): String =
        text
            .replace(BEARER) { "${it.groupValues[1]}${it.groupValues[2]}[masked]" }
            .replace(EMAIL, "[email]")
            .replace(PHONE, "[phone]")
            .replace(LONG_SECRET, "[masked]")
}
