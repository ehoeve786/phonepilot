package app.pocketpilot.security

import android.content.Context
import app.pocketpilot.core.policy.ToolMode
import app.pocketpilot.core.policy.ToolModes
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
) : ToolModes {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _modes = MutableStateFlow(load())
    val modes: StateFlow<Map<String, ToolMode>> = _modes.asStateFlow()

    override fun modeFor(tool: String): ToolMode? = _modes.value[tool]

    /** Sets [tool] to [mode]; null returns it to its tier's default. */
    fun set(
        tool: String,
        mode: ToolMode?,
    ) {
        val editor = prefs.edit()
        if (mode == null) editor.remove(tool) else editor.putString(tool, mode.name)
        editor.apply()
        _modes.update { if (mode == null) it - tool else it + (tool to mode) }
    }

    private fun load(): Map<String, ToolMode> =
        prefs.all
            .mapNotNull { (tool, value) ->
                ToolMode.entries.firstOrNull { it.name == value }?.let { tool to it }
            }.toMap()

    private companion object {
        const val PREFS = "tool_modes"
    }
}
