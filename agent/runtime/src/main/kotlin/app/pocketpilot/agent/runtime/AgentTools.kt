package app.pocketpilot.agent.runtime

import app.pocketpilot.agent.providers.api.ToolDef
import app.pocketpilot.core.model.ToolSpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The tools a run may call, under names every provider accepts. Tool names such as `ui.tap` contain
 * dots, which the Anthropic, OpenAI and Gemini APIs reject, so each gets a wire name (`ui_tap`) and
 * calls are mapped back by lookup.
 *
 * Full definitions of every tool cost several thousand tokens on each request, so a run starts with
 * the [CORE] tools only. The rest appear in [catalog] as one line each, with their arguments; the model
 * loads their definitions with [LOAD], or calls one directly, which loads it too.
 */
internal class AgentTools(
    specs: List<ToolSpec>,
) {
    private val byWire: Map<String, ToolSpec>
    private val allDefs: List<ToolDef>
    private val loaded = HashSet<String>()

    init {
        val used = HashSet<String>(listOf(LOAD))
        val pairs =
            specs.map { spec ->
                var wire = spec.name.replace('.', '_').take(MAX_NAME)
                var n = 2
                while (!used.add(wire)) wire = "${spec.name.replace('.', '_').take(MAX_NAME - 3)}_${n++}"
                wire to spec
            }
        byWire = pairs.toMap()
        allDefs = pairs.map { (wire, spec) -> ToolDef(wire, spec.description, portable(spec.inputSchema)) }
        val deferred = specs.filter { it.name !in CORE }
        if (deferred.size <=
            MIN_DEFERRED
        ) {
            specs.forEach { loaded += it.name }
        } else {
            specs.filter { it.name in CORE }.forEach { loaded += it.name }
        }
    }

    /** Definitions to send: the loaded tools, in registration order, and [LOAD] while anything is left to load. */
    val defs: List<ToolDef>
        get() =
            allDefs.filter { byWire.getValue(it.name).name in loaded } +
                listOfNotNull(loadDef.takeIf { byWire.values.any { it.name !in loaded } })

    /**
     * One line per tool that starts unloaded, "name: title. Arguments: a, b (optional)". It stays the same
     * for the whole run, so the system prompt does not change and providers can cache it.
     */
    val catalog: String =
        byWire.values
            .filter { it.name !in loaded }
            .joinToString("\n") { spec ->
                val arguments = signature(spec.inputSchema).let { if (it.isEmpty()) "" else ". Arguments: $it" }
                "- ${spec.name}: ${spec.title}$arguments"
            }

    /**
     * The tool for a name the model used: the wire name, or the tool's own dotted name. Some models copy a
     * whole catalog entry, such as "device.battery_usage(top=10)", so anything from "(" or ":" on is ignored.
     */
    fun resolve(name: String): ToolSpec? {
        val bare = name.substringBefore('(').substringBefore(':').trim()
        return byWire[bare] ?: byWire.values.firstOrNull { it.name == bare }
    }

    fun has(toolName: String): Boolean = byWire.values.any { it.name == toolName }

    fun isLoadCall(name: String): Boolean = name == LOAD || name == LOAD_DOTTED

    /** Adds the tool's full definition to later requests. */
    fun markUsed(spec: ToolSpec) {
        loaded += spec.name
    }

    /** Loads the tools named in a [LOAD] call and says which. */
    fun load(names: List<String>): String {
        val found = names.mapNotNull { resolve(it.trim()) }
        found.forEach(::markUsed)
        val unknown = names.filter { resolve(it.trim()) == null }
        return buildString {
            if (found.isNotEmpty()) append("Loaded ${found.joinToString { it.name }}; their definitions are in your tools now.")
            if (unknown.isNotEmpty()) append(" No tool named ${unknown.joinToString()}.")
        }.trim().ifEmpty { "Name the tools to load." }
    }

    internal companion object {
        const val MAX_NAME = 64
        const val LOAD = "tools_load"
        const val LOAD_DOTTED = "tools.load"

        /** Below this many other tools, loading on demand saves too little to be worth a step. */
        private const val MIN_DEFERRED = 6

        /** Tools nearly every task needs, sent in full from the start. */
        val CORE =
            setOf(
                "screen.snapshot",
                "screen.capture",
                "ui.tap",
                "ui.type_text",
                "ui.swipe",
                "ui.press_key",
                "ui.global_action",
                "app.launch",
                "app.list",
                "app.open_url",
                "app.activities",
                "app.start_activity",
                "settings.get",
                "settings.set",
                "adb.query",
            )

        private val loadDef =
            ToolDef(
                LOAD,
                "Loads the full definitions of tools from the list of more tools in your instructions, so you can call them.",
                JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("object"),
                        "properties" to
                            JsonObject(
                                mapOf(
                                    "names" to
                                        JsonObject(
                                            mapOf(
                                                "type" to JsonPrimitive("array"),
                                                "items" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                                "description" to JsonPrimitive("Tool names, such as notify.list"),
                                            ),
                                        ),
                                ),
                            ),
                        "required" to JsonArray(listOf(JsonPrimitive("names"))),
                    ),
                ),
            )

        /** "a (text), b (number, optional)" for required text a and optional number b. */
        fun signature(schema: JsonObject): String {
            val properties = (schema["properties"] as? JsonObject).orEmpty()
            val required = (schema["required"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty().toSet()
            return properties.entries.joinToString { (name, property) ->
                val type =
                    when (((property as? JsonObject)?.get("type") as? JsonPrimitive)?.content) {
                        "integer", "number" -> "number"
                        "boolean" -> "true or false"
                        "array" -> "list"
                        "object" -> "object"
                        else -> "text"
                    }
                "$name ($type${if (name in required) "" else ", optional"})"
            }
        }

        /**
         * The schema with an `items` schema on every array: OpenAI and several compatible servers
         * reject an array without one, and Gemini needs a typed one (its adapter asks for JSON text).
         */
        fun portable(schema: JsonObject): JsonObject {
            val fixed =
                schema.mapValues { (key, value) ->
                    when {
                        key == "properties" && value is JsonObject -> {
                            JsonObject(value.mapValues { (_, child) -> (child as? JsonObject)?.let(::portable) ?: child })
                        }

                        key in NESTED && value is JsonObject -> {
                            portable(value)
                        }

                        key == "anyOf" && value is JsonArray -> {
                            JsonArray(value.map { (it as? JsonObject)?.let(::portable) ?: it })
                        }

                        else -> {
                            value
                        }
                    }
                }
            val isArray = (schema["type"] as? JsonPrimitive)?.content == "array"
            return JsonObject(if (isArray && "items" !in fixed) fixed + ("items" to JsonObject(emptyMap())) else fixed)
        }

        private val NESTED = setOf("items", "additionalProperties")
    }
}
