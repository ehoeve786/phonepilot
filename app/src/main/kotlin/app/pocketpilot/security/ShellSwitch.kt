package app.pocketpilot.security

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The owner's switch for the raw shell tool (shell.exec). Off until the owner turns it on; even then
 * every command is shown in full and confirmed before it runs.
 */
class ShellSwitch(
    context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY, false))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun set(on: Boolean) {
        prefs.edit().putBoolean(KEY, on).apply()
        _enabled.value = on
    }

    private companion object {
        const val PREFS = "shell"
        const val KEY = "enabled"
    }
}
