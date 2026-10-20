package app.pocketpilot.security

import android.content.Context
import app.pocketpilot.core.policy.ToolMode
import app.pocketpilot.core.policy.ToolModes
import app.pocketpilot.network.api.SecretStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The owner's Allow, Ask or Block per tool, from the Tools screen. Tools missing from the map keep
 * their tier's default; the policy engine ignores a mode the tier does not permit.
 */
class ToolModeStore(
    context: Context,
    private val secrets: SecretStore,
) : ToolModes {
    private val _modes = MutableStateFlow(readOrMigrate(context))
    val modes: StateFlow<Map<String, ToolMode>> = _modes.asStateFlow()

    override fun modeFor(tool: String): ToolMode? = _modes.value[tool]

    /** Sets [tool] to [mode]; null returns it to its tier's default. */
    fun set(
        tool: String,
        mode: ToolMode?,
    ) {
        require(!tool.contains('=') && !tool.contains('\n')) { "Bad tool name: $tool" }
        _modes.update { if (mode == null) it - tool else it + (tool to mode) }
        secrets.put(NAME, encode(_modes.value))
    }

    /** Kept in the Keystore-backed store, so editing a shared-preferences file cannot unblock a tool. */
    private fun readOrMigrate(context: Context): Map<String, ToolMode> {
        val legacy = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        if (legacy.all.isNotEmpty()) {
            val old =
                legacy.all
                    .mapNotNull { (tool, value) ->
                        ToolMode.entries.firstOrNull { it.name == value }?.let { tool to it }
                    }.toMap()
            if (secrets.get(NAME) == null) secrets.put(NAME, encode(old))
            legacy.edit().clear().apply()
        }
        return decode(secrets.get(NAME).orEmpty())
    }

    private fun encode(modes: Map<String, ToolMode>): String = modes.entries.joinToString("\n") { "${it.key}=${it.value.name}" }

    private fun decode(text: String): Map<String, ToolMode> =
        text
            .lines()
            .mapNotNull { line ->
                val mode = ToolMode.entries.firstOrNull { it.name == line.substringAfter('=', "") }
                val tool = line.substringBefore('=')
                if (tool.isNotEmpty() && mode != null) tool to mode else null
            }.toMap()

    private companion object {
        const val NAME = "tool_modes"
        const val LEGACY_PREFS = "tool_modes"
    }
}
