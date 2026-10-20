package app.pocketpilot.security

import android.content.Context
import app.pocketpilot.network.api.SecretStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The owner's switch for the raw shell tool (shell.exec). Off until the owner turns it on; even then
 * every command is shown in full and confirmed before it runs.
 */
class ShellSwitch(
    context: Context,
    private val secrets: SecretStore,
) {
    private val _enabled = MutableStateFlow(readOrMigrate(context))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun set(on: Boolean) {
        secrets.put(NAME, on.toString())
        _enabled.value = on
    }

    /** Kept in the Keystore-backed store, so editing a shared-preferences file cannot switch shell on. */
    private fun readOrMigrate(context: Context): Boolean {
        val legacy = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        if (legacy.contains(LEGACY_KEY)) {
            if (secrets.get(NAME) == null) secrets.put(NAME, legacy.getBoolean(LEGACY_KEY, false).toString())
            legacy.edit().remove(LEGACY_KEY).apply()
        }
        return secrets.get(NAME)?.toBooleanStrictOrNull() ?: false
    }

    private companion object {
        const val NAME = "shell_enabled"
        const val LEGACY_PREFS = "shell"
        const val LEGACY_KEY = "enabled"
    }
}
