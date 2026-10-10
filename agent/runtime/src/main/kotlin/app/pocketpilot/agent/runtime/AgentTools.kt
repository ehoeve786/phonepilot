package app.pocketpilot.agent.runtime

import app.pocketpilot.agent.providers.api.ToolDef
import app.pocketpilot.core.model.ToolSpec

/**
 * The tools a run may call, under names every provider accepts. Tool names such as `ui.tap` contain
 * dots, which the Anthropic, OpenAI and Gemini APIs reject, so each gets a wire name (`ui_tap`) and
 * calls are mapped back by lookup.
 */
internal class AgentTools(
    specs: List<ToolSpec>,
) {
    private val byWire: Map<String, ToolSpec>
    val defs: List<ToolDef>

    init {
        val used = HashSet<String>()
        val pairs =
            specs.map { spec ->
                var wire = spec.name.replace('.', '_').take(MAX_NAME)
                var n = 2
                while (!used.add(wire)) wire = "${spec.name.replace('.', '_').take(MAX_NAME - 3)}_${n++}"
                wire to spec
            }
        byWire = pairs.toMap()
        defs = pairs.map { (wire, spec) -> ToolDef(wire, spec.description, spec.inputSchema) }
    }

    /** The tool for a name the model used: the wire name, or the tool's own dotted name. */
    fun resolve(name: String): ToolSpec? = byWire[name] ?: byWire.values.firstOrNull { it.name == name }

    fun has(toolName: String): Boolean = byWire.values.any { it.name == toolName }

    private companion object {
        const val MAX_NAME = 64
    }
}
