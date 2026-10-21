package app.pocketpilot.ui

import android.app.Activity
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/** Keeps the window out of screenshots and the recents preview while [secure] is true. */
@Composable
internal fun SecureWindow(secure: Boolean) {
    val window = (LocalContext.current as? Activity)?.window
    DisposableEffect(window, secure) {
        if (secure) window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (secure) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}
