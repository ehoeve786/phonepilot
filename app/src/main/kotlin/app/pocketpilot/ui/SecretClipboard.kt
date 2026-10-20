package app.pocketpilot.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import app.pocketpilot.designsystem.copySensitive

/**
 * Copies text that holds the local token. The clip is marked sensitive, so the clipboard preview and
 * keyboards do not show it, and it is cleared after [CLEAR_AFTER_MS] unless something else was copied.
 */
fun copySecret(
    context: Context,
    text: String,
) {
    val clipboard = copySensitive(context, LABEL, text)
    Handler(Looper.getMainLooper()).postDelayed({
        // Android only lets an app read the clip description while in front; otherwise leave the clip alone.
        if (runCatching { clipboard.primaryClipDescription?.label == LABEL }.getOrDefault(false)) clipboard.clearPrimaryClip()
    }, CLEAR_AFTER_MS)
}

/** Copies a crash report: marked sensitive, and it stays until something else is copied. */
fun copyReport(
    context: Context,
    text: String,
) {
    copySensitive(context, REPORT_LABEL, text)
}

private const val LABEL = "PocketPilot token"
private const val REPORT_LABEL = "PocketPilot report"
private const val CLEAR_AFTER_MS = 2 * 60 * 1000L
