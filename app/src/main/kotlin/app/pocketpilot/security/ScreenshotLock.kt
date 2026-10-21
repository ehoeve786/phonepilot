package app.pocketpilot.security

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the screens that show the local token and keys stay out of screenshots and the recents preview.
 * On by default. The owner can turn it off in Settings, which asks for their fingerprint or screen lock first.
 */
class ScreenshotLock(
    context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY, true))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun set(on: Boolean) {
        prefs.edit().putBoolean(KEY, on).apply()
        _enabled.value = on
    }

    private companion object {
        const val PREFS = "pocketpilot_privacy"
        const val KEY = "lock_secrets"
    }
}
