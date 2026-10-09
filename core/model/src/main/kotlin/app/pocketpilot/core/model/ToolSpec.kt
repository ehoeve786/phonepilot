package app.pocketpilot.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Hints mapped one-to-one onto MCP tool annotations. */
@Serializable
data class ToolAnnotations(
    val readOnly: Boolean = false,
    val destructive: Boolean = false,
    val idempotent: Boolean = false,
    val openWorld: Boolean = false,
)

/** Where a tool came from, for namespacing and audit. */
@Serializable
sealed interface ToolSource {
    @Serializable
    data object Core : ToolSource

    @Serializable
    data class Plugin(
        val id: String,
    ) : ToolSource

    @Serializable
    data class Mount(
        val id: String,
    ) : ToolSource
}

/** Compile-time and runtime gating for a tool. */
@Serializable
data class Availability(
    val phase: Phase,
    val flavors: Set<Flavor> = Flavor.entries.toSet(),
)

/**
 * The contract for one device action (spec section 4). Every call made by an MCP client, the agent
 * or a plugin is a call against a [ToolSpec].
 */
@Serializable
data class ToolSpec(
    /** `namespace.verb`, unique, for example `ui.tap`. */
    val name: String,
    val title: String,
    /** Written for models. */
    val description: String,
    /** JSON Schema 2020-12. */
    val inputSchema: JsonObject,
    /** JSON Schema 2020-12; enables MCP structured content when present. */
    val outputSchema: JsonObject? = null,
    val riskTier: RiskTier,
    /** All of these must be granted to the session. */
    val requiredScopes: Set<Scope>,
    /** The tool is hidden unless all of these are healthy. */
    val requiredCapabilities: Set<CapabilityId> = emptySet(),
    val annotations: ToolAnnotations = ToolAnnotations(),
    val source: ToolSource = ToolSource.Core,
    val availability: Availability,
) {
    init {
        require(isValidName(name)) { "Tool name must be namespace.verb, got '$name'" }
    }

    val namespace: String get() = name.substringBefore('.')

    companion object {
        private val NAME = Regex("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$")

        /** `ui.tap`, and for mounts `mount.<id>.<tool>`. */
        fun isValidName(name: String): Boolean = NAME.matches(name)
    }
}
