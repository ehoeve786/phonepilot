package app.pocketpilot.designsystem

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle

/**
 * Copies text the owner pastes elsewhere, such as a transcript or a crash report. The clip is marked sensitive, so the
 * clipboard preview and keyboards do not show it, and it stays until something else is copied.
 */
fun copySensitive(
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
