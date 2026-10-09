package app.pocketpilot.core.orchestrator

import app.pocketpilot.core.model.PolicyProfile
import app.pocketpilot.core.model.ToolSpec

/** All tools this build offers, filtered by the flavor's [PolicyProfile]. */
class ToolRegistry(
    handlers: Set<ToolHandler>,
    policyProfile: PolicyProfile,
) {
    private val byName: Map<String, ToolHandler>

    init {
        val duplicates = handlers.groupBy { it.spec.name }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "Duplicate tool names: $duplicates" }
        byName =
            handlers
                .filter { policyProfile.flavor in it.spec.availability.flavors }
                .associateBy { it.spec.name }
                .toSortedMap()
    }

    val specs: List<ToolSpec> get() = byName.values.map { it.spec }

    fun handler(name: String): ToolHandler? = byName[name]
}
