package app.pocketpilot.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.Date

/**
 * Remembers why the app last crashed, so the owner can copy the details from the home screen
 * instead of needing adb and logcat. It combines the stack trace of an uncaught Java exception, the
 * system's exit record (which also covers native crashes, such as one in the Go library) and the
 * last lines of the Tailscale node's log. Nothing leaves the phone unless the owner copies it.
 */
class CrashReports(
    private val context: Context,
) {
    private val javaCrash = File(context.filesDir, "last-crash.txt")
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val mutableReport = MutableStateFlow<String?>(null)
    val report: StateFlow<String?> = mutableReport.asStateFlow()

    /** Records uncaught exceptions in this process, then lets Android handle them as usual. */
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                javaCrash.writeText("${Date()} on thread ${thread.name}\n${error.stackTraceToString()}")
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Builds the report of the last crash the owner has not dismissed yet. */
    fun load() {
        val parts = mutableListOf<String>()
        runCatching { exitRecord() }.getOrNull()?.let(parts::add)
        runCatching { javaCrash.readText() }.getOrNull()?.takeIf(String::isNotBlank)?.let { parts += "Java exception: $it" }
        if (parts.isEmpty()) return
        runCatching { File(context.noBackupFilesDir, "tailscale/node.log").readLines().takeLast(LOG_LINES) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?.let { parts += "Tailscale log:\n" + it.joinToString("\n") }
        mutableReport.value = parts.joinToString("\n\n")
    }

    fun dismiss() {
        runCatching { javaCrash.delete() }
        prefs.edit().putLong(KEY_SEEN, System.currentTimeMillis()).apply()
        mutableReport.value = null
    }

    private fun exitRecord(): String? {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val exit =
            activityManager
                .getHistoricalProcessExitReasons(context.packageName, 0, MAX_EXITS)
                .firstOrNull { it.processName == context.packageName }
                ?: return null
        if (exit.timestamp <= prefs.getLong(KEY_SEEN, 0L)) return null
        val kind =
            when (exit.reason) {
                ApplicationExitInfo.REASON_CRASH -> "Java crash"
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash"
                ApplicationExitInfo.REASON_ANR -> "Not responding"
                else -> return null
            }
        return buildString {
            append("$kind at ${Date(exit.timestamp)}: ${exit.description.orEmpty()}")
            // For native crashes this is the tombstone; its readable strings hold the backtrace.
            exit.traceInputStream?.use { stream ->
                val text = printableStrings(stream.readBytes()).take(TRACE_LINES)
                if (text.isNotEmpty()) append("\n").append(text.joinToString("\n"))
            }
        }
    }

    private fun printableStrings(bytes: ByteArray): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        for (byte in bytes) {
            val c = byte.toInt() and BYTE_MASK
            if (c in PRINTABLE || c == '\t'.code) {
                current.append(c.toChar())
            } else {
                if (current.length >= MIN_STRING) out += current.toString()
                current.clear()
            }
        }
        if (current.length >= MIN_STRING) out += current.toString()
        return out
    }

    private companion object {
        const val PREFS = "crash_reports"
        const val KEY_SEEN = "seen_until"
        const val MAX_EXITS = 5
        const val LOG_LINES = 60
        const val TRACE_LINES = 150
        const val MIN_STRING = 6
        const val BYTE_MASK = 0xFF
        val PRINTABLE = 0x20..0x7E
    }
}
