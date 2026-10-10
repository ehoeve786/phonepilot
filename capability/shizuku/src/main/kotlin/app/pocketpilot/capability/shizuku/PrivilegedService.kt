package app.pocketpilot.capability.shizuku

import android.content.Context
import android.os.ParcelFileDescriptor
import app.pocketpilot.capability.api.settings.SettingKey
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Runs as the shell user in a process Shizuku starts. Every method runs one fixed command with
 * arguments passed as an argv array, never through a shell, and checks its inputs first, so the app
 * side cannot turn it into a general shell (spec section 6). Raw shell arrives later behind shell:exec.
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

    override fun captureScreen(): ParcelFileDescriptor = pipe(ProcessBuilder("screencap").start())

    override fun dumpUiHierarchy(): ParcelFileDescriptor {
        val file = File(UI_DUMP_PATH)
        file.delete()
        val output = run("uiautomator", "dump", UI_DUMP_PATH)
        check(file.exists()) { "uiautomator could not read the screen: ${output.trim()}" }
        val bytes = file.readBytes()
        file.delete()
        return pipe(bytes)
    }

    override fun foregroundActivity(): String {
        val output = run("dumpsys", "activity", "activities")
        return ForegroundParser.parse(output).orEmpty()
    }

    override fun launchPackage(packageName: String) {
        require(PACKAGE.matches(packageName)) { "Not a package name" }
        run("monkey", "-p", packageName, "-c", "android.intent.category.LAUNCHER", "1")
    }

    override fun openUrl(url: String) {
        require(url.startsWith("https://") || url.startsWith("http://")) { "Only http and https links can be opened" }
        run("am", "start", "-a", "android.intent.action.VIEW", "-d", url)
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
        const val VERSION = 2

        private const val UI_DUMP_PATH = "/data/local/tmp/pocketpilot-ui.xml"
        private const val COMMAND_TIMEOUT_S = 15L
        private const val MAX_GESTURE_MS = 10_000L
        private const val MAX_KEYS = 256
        private const val MAX_KEYCODE = 400
        private const val MAX_TEXT = 2_000
        private const val MAX_ERROR = 300
        private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
    }
}
