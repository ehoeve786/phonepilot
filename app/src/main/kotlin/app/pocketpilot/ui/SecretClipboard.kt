package app.pocketpilot.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle

/**
 * Copies text that holds the local token. The clip is marked sensitive, so the clipboard preview and
 * keyboards do not show it, and it is cleared after [CLEAR_AFTER_MS] unless something else was copied.
 */
fun copySecret(
    context: Context,
    text: String,
) {
    val clipboard = setSensitiveClip(context, LABEL, text)
    Handler(Looper.getMainLooper()).postDelayed({
        // Android only lets an app read the clip description while in front; otherwise leave the clip alone.
        if (runCatching { clipboard.primaryClipDescription?.label == LABEL }.getOrDefault(false)) clipboard.clearPrimaryClip()
    }, CLEAR_AFTER_MS)
}

/**
 * Copies a crash report or similar text the owner pastes elsewhere. It is marked sensitive, so the clipboard preview
 * does not show it, and it stays until something else is copied.
 */
fun copyReport(
    context: Context,
    text: String,
) {
    setSensitiveClip(context, REPORT_LABEL, text)
}

private fun setSensitiveClip(
    context: Context,
    label: String,
    text: String,
): ClipboardManager {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    val clip =
        ClipData.newPlainText(label, text).apply {
            description.extras = PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }
        }
    clipboard.setPrimaryClip(clip)
    return clipboard
}

/** [ClipDescription.EXTRA_IS_SENSITIVE], whose value older Android versions honour too. */
private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
private const val LABEL = "PocketPilot token"
private const val REPORT_LABEL = "PocketPilot report"
private const val CLEAR_AFTER_MS = 2 * 60 * 1000L
