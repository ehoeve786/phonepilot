package app.pocketpilot.capability.shizuku

import android.content.Context
import android.os.ParcelFileDescriptor
import app.pocketpilot.capability.api.apps.AppAdminArgs
import app.pocketpilot.capability.api.apps.IntentArgs
import app.pocketpilot.capability.api.settings.SettingKey
import app.pocketpilot.capability.api.shell.AdbQuery
import java.io.BufferedReader
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Runs as the shell user in a process Shizuku starts. Every method runs one fixed command with
 * arguments passed as an argv array, never through a shell, and checks its inputs first, so the app
 * side cannot turn it into a general shell (spec section 6). [runReadOnly] runs only commands
 * [AdbQuery] allows. The one exception is [runShell], which backs `shell.exec`: off by default in the
 * app and confirmed by the owner.
 */
class PrivilegedService() : IPocketPilotPrivileged.Stub() {
    /** Shizuku 13 calls this constructor when it exists. */
    @Suppress("UNUSED_PARAMETER")
    constructor(context: Context) : this()

    override fun getVersion(): Int = VERSION

    override fun tap(
        x: Int,
        y: Int,
    ) {
        run("input", "tap", "$x", "$y")
    }

    override fun swipe(
        fromX: Int,
        fromY: Int,
        toX: Int,
        toY: Int,
        durationMs: Long,
    ) {
        require(durationMs in 1..MAX_GESTURE_MS) { "durationMs out of range" }
        run("input", "swipe", "$fromX", "$fromY", "$toX", "$toY", "$durationMs")
    }

    override fun keyEvents(keyCodes: IntArray) {
        require(keyCodes.size in 1..MAX_KEYS && keyCodes.all { it in 1..MAX_KEYCODE }) { "Bad key codes" }
        run("input", "keyevent", *keyCodes.map(Int::toString).toTypedArray())
    }

    override fun text(text: String) {
        require(text.length <= MAX_TEXT && text.all { it.code in 0x20..0x7e }) { "Only printable ASCII can be typed through Shizuku" }
        // `input text` reads %s as a space and cannot take a literal space.
        run("input", "text", text.replace(" ", "%s"))
    }

    override fun captureScreen(): ParcelFileDescriptor {
        val process = ProcessBuilder("screencap").start()
        // A screenshot that hangs must not hold its pipe open forever.
        thread(name = "pp-capture-timeout") {
            if (!process.waitFor(COMMAND_TIMEOUT_S, TimeUnit.SECONDS)) process.destroyForcibly()
        }
        return pipe(process)
    }

    override fun dumpUiHierarchy(): ParcelFileDescriptor {
        // A random name, since /data/local/tmp is shared: nothing else can predict the file to plant a link at.
        val file = File("/data/local/tmp/pocketpilot-ui-${UUID.randomUUID()}.xml")
        try {
            val output = run("uiautomator", "dump", file.path)
            check(file.exists()) { "uiautomator could not read the screen: ${output.trim()}" }
            check(!Files.isSymbolicLink(file.toPath())) { "The screen dump was replaced by a link; not reading it" }
            return pipe(file.readBytes())
        } finally {
            file.delete()
        }
    }

    override fun foregroundActivity(): String {
        val output = run("dumpsys", "activity", "activities")
        return ForegroundParser.parse(output).orEmpty()
    }

    override fun launchPackage(packageName: String) {
        require(PACKAGE.matches(packageName)) { "Not a package name" }
        run("monkey", "-p", packageName, "-c", "android.intent.category.LAUNCHER", "1")
    }

    override fun openUrl(
        url: String,
        packageName: String,
    ): String {
        require(url.startsWith("https://") || url.startsWith("http://")) { "Only http and https links can be opened" }
        if (packageName.isEmpty()) return run("am", "start", "-a", "android.intent.action.VIEW", "-d", url)
        require(PACKAGE.matches(packageName)) { "Not a package name" }
        return run("am", "start", "-a", "android.intent.action.VIEW", "-d", url, "-p", packageName)
    }

    override fun expandStatusBar(quickSettings: Boolean) {
        run("cmd", "statusbar", if (quickSettings) "expand-settings" else "expand-notifications")
    }

    override fun readSetting(key: String): String = settings.get(settingKey(key))

    override fun writeSetting(
        key: String,
        value: String,
    ) {
        settings.set(settingKey(key), value)
    }

    override fun forceStop(packageName: String) {
        mutable(packageName)
        AppAdminArgs.checkNotCritical(packageName)
        run("am", "force-stop", packageName)
    }

    override fun dumpPackage(packageName: String): String {
        AppAdminArgs.checkPackage(packageName)
        return AppAdminParsers.trimDump(run("dumpsys", "package", packageName), MAX_DUMP)
    }

    override fun appOps(packageName: String): String {
        AppAdminArgs.checkPackage(packageName)
        return run("appops", "get", packageName).take(MAX_DUMP)
    }

    override fun setPermission(
        packageName: String,
        permission: String,
        granted: Boolean,
    ) {
        mutable(packageName)
        AppAdminArgs.checkRuntimePermission(permission)
        run("pm", if (granted) "grant" else "revoke", packageName, permission)
    }

    override fun setAppOp(
        packageName: String,
        op: String,
        mode: String,
    ) {
        mutable(packageName)
        AppAdminArgs.checkOpChange(op, mode)
        run("appops", "set", packageName, op, mode)
    }

    override fun setPackageEnabled(
        packageName: String,
        enabled: Boolean,
    ) {
        mutable(packageName)
        AppAdminArgs.checkNotCritical(packageName)
        if (enabled) run("pm", "enable", packageName) else run("pm", "disable-user", "--user", "0", packageName)
    }

    override fun clearPackageData(packageName: String) {
        mutable(packageName)
        AppAdminArgs.checkNotCritical(packageName)
        run("pm", "clear", packageName)
    }

    override fun runShell(
        command: String,
        timeoutMs: Long,
    ): String {
        require(command.isNotBlank() && command.length <= MAX_COMMAND) { "The command must be 1 to $MAX_COMMAND characters" }
        return execute(ProcessBuilder("sh", "-c", command), "", timeoutMs, keepEnd = false)
    }

    override fun runReadOnly(
        argv: Array<String>,
        filter: String,
        timeoutMs: Long,
    ): String {
        val words = argv.toList()
        require(AdbQuery.allowed(words)) { "Not a read-only command: ${words.joinToString(" ").take(MAX_ERROR)}" }
        require(filter.length <= MAX_FILTER) { "The filter may be at most $MAX_FILTER characters" }
        // Logs and crash reports are newest last, so long output keeps its end.
        val keepEnd = words[0] == "logcat" || words.getOrNull(1) == "dropbox"
        return execute(ProcessBuilder(words), filter, timeoutMs, keepEnd)
    }

    override fun setDozeExempt(
        packageName: String,
        exempt: Boolean,
    ) {
        mutable(packageName)
        run("cmd", "deviceidle", "whitelist", (if (exempt) "+" else "-") + packageName)
    }

    override fun setStandbyBucket(
        packageName: String,
        bucket: String,
    ) {
        mutable(packageName)
        AppAdminArgs.checkStandbyBucket(bucket)
        run("am", "set-standby-bucket", packageName, bucket)
    }

    override fun startIntent(
        args: Array<String>,
        broadcast: Boolean,
    ): String {
        val list = args.toList()
        IntentArgs.check(list)
        val command = if (broadcast) listOf("am", "broadcast") else listOf("am", "start", "-W")
        val output = run(*(command + list).toTypedArray()).take(MAX_ERROR * 10).trim()
        check(!output.lineSequence().any { it.startsWith("Error") }) { output }
        return output
    }

    /**
     * Runs [builder] until it ends or [timeoutMs] passes. Returns exit code, stdout and stderr separated
     * by NUL characters; the exit code is -1 on timeout. Stdout keeps only lines containing [filter], and
     * its end rather than its start when [keepEnd] and it is too long.
     */
    private fun execute(
        builder: ProcessBuilder,
        filter: String,
        timeoutMs: Long,
        keepEnd: Boolean,
    ): String {
        val process = builder.start()
        process.outputStream.close()
        val stdout = StringBuilder()
        var stderr = ""
        val out =
            thread(name = "pp-shell-out") {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (filter.isEmpty() || line.contains(filter, ignoreCase = true)) {
                            synchronized(stdout) {
                                if (keepEnd || stdout.length < MAX_SHELL_OUTPUT) stdout.append(line).append('\n')
                                if (keepEnd && stdout.length > 2 * MAX_SHELL_OUTPUT) stdout.delete(0, stdout.length - MAX_SHELL_OUTPUT)
                            }
                        }
                    }
                }
            }
        val err = thread(name = "pp-shell-err") { stderr = process.errorStream.bufferedReader().use { it.readCapped(MAX_SHELL_OUTPUT) } }
        val finished = process.waitFor(timeoutMs.coerceIn(1_000, MAX_SHELL_MS), TimeUnit.MILLISECONDS)
        if (!finished) process.destroyForcibly()
        out.join(STREAM_JOIN_MS)
        err.join(STREAM_JOIN_MS)
        val exit = if (finished) process.exitValue() else -1
        val text =
            synchronized(
                stdout,
            ) { stdout.toString() }.let { if (keepEnd) it.takeLast(MAX_SHELL_OUTPUT) else it.take(MAX_SHELL_OUTPUT) }
        return "$exit\u0000$text\u0000${stderr.take(MAX_SHELL_OUTPUT)}"
    }

    private fun mutable(packageName: String) {
        AppAdminArgs.checkPackage(packageName)
        AppAdminArgs.checkNotProtected(packageName)
    }

    private val settings = SettingCommands { argv -> run(*argv.toTypedArray()) }

    private fun settingKey(wire: String): SettingKey = requireNotNull(SettingKey.fromWire(wire)) { "Not an allowed setting: $wire" }

    override fun destroy() {
        exitProcess(0)
    }

    /** Runs [argv] and returns its output; throws when it fails or outlives [COMMAND_TIMEOUT_S]. */
    private fun run(vararg argv: String): String {
        val process = ProcessBuilder(*argv).redirectErrorStream(true).start()
        var output = ""
        val reader = thread(name = "pp-output") { output = process.inputStream.bufferedReader().readText() }
        if (!process.waitFor(COMMAND_TIMEOUT_S, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw IllegalStateException("${argv.first()} timed out")
        }
        reader.join(TimeUnit.SECONDS.toMillis(1))
        check(process.exitValue() == 0) { "${argv.first()} failed: ${output.take(MAX_ERROR).trim()}" }
        return output
    }

    /** Reads at most [max] characters, draining the rest so the process never blocks on a full pipe. */
    private fun BufferedReader.readCapped(max: Int): String {
        val out = StringBuilder()
        val buffer = CharArray(READ_CHUNK)
        while (true) {
            val n = read(buffer)
            if (n < 0) break
            if (out.length < max) out.append(buffer, 0, minOf(n, max - out.length))
        }
        return out.toString()
    }

    private fun pipe(process: Process): ParcelFileDescriptor {
        val (read, write) = ParcelFileDescriptor.createPipe()
        thread(name = "pp-pipe") {
            ParcelFileDescriptor.AutoCloseOutputStream(write).use { out -> process.inputStream.use { it.copyTo(out) } }
            process.waitFor(COMMAND_TIMEOUT_S, TimeUnit.SECONDS)
        }
        return read
    }

    private fun pipe(bytes: ByteArray): ParcelFileDescriptor {
        val (read, write) = ParcelFileDescriptor.createPipe()
        thread(name = "pp-pipe") { ParcelFileDescriptor.AutoCloseOutputStream(write).use { it.write(bytes) } }
        return read
    }

    companion object {
        /** The AIDL contract version; bump with every change to IPocketPilotPrivileged. */
        const val VERSION = 7

        private const val COMMAND_TIMEOUT_S = 15L
        private const val READ_CHUNK = 4096
        private const val MAX_GESTURE_MS = 10_000L
        private const val MAX_KEYS = 256
        private const val MAX_KEYCODE = 400
        private const val MAX_TEXT = 2_000
        private const val MAX_ERROR = 300
        private const val MAX_DUMP = 200_000
        private const val MAX_COMMAND = 4_000
        private const val MAX_FILTER = 200
        private const val MAX_SHELL_MS = 120_000L
        private const val MAX_SHELL_OUTPUT = 60_000
        private const val STREAM_JOIN_MS = 2_000L
        private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
    }
}
