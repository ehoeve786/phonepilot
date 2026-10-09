package app.pocketpilot.buildlogic

/** One declared project-to-project dependency, e.g. `:app` -> `:core:model` via `implementation`. */
data class ModuleEdge(
    val from: String,
    val to: String,
    val configuration: String,
)

/**
 * The dependency rules from spec section 2, checked on every pull request by `checkModuleGraph`.
 * Paths are Gradle project paths, which mirror the folder layout (`:core:policy`).
 */
object ModuleGraphRules {
    private val pureKotlin = setOf(":core:model", ":core:common")
    private val capabilityAllowed = setOf(":capability:api", ":core:model", ":core:common")
    private val orchestratorOnly = setOf("server", "agent", "plugins", "network")
    private val proConfigurationPrefixes = listOf("pro", "play")

    fun violations(edges: Iterable<ModuleEdge>): List<String> =
        edges
            .distinct()
            // AGP wires each module's test variants to the module itself; that is not a real edge.
            .filter { it.from != it.to }
            .flatMap(::check)
            .distinct()
            .sorted()

    private fun check(edge: ModuleEdge): List<String> {
        val (from, to, configuration) = edge
        val problems = mutableListOf<String>()

        fun fail(rule: String) {
            problems += "$from -> $to ($configuration): $rule"
        }

        if (from in pureKotlin) {
            fail("core/model and core/common must not depend on any other module")
        }
        if (from.startsWith(":capability:") && from != ":capability:api" && to !in capabilityAllowed) {
            fail("capability implementations may depend only on capability/api, core/model and core/common")
        }
        if (from.topLevel() in orchestratorOnly && to.startsWith(":capability:")) {
            fail("${from.topLevel()} must reach device actions through core/orchestrator, not a capability")
        }
        if (from.startsWith(":core:") && !to.startsWith(":core:") && to != ":capability:api") {
            fail("core modules may depend only on other core modules and capability/api")
        }
        if (from.startsWith(":feature:") && to.startsWith(":feature:")) {
            fail("feature modules must not depend on each other; shared UI goes in designsystem")
        }
        if (to == ":app") {
            fail("no module may depend on the app module")
        }
        if (to.startsWith(":pro:") && !from.startsWith(":pro:") && !from.isAppProFlavor(configuration)) {
            fail("open modules must not depend on pro modules")
        }
        return problems
    }

    private fun String.topLevel(): String = removePrefix(":").substringBefore(":")

    /** The app may pull in pro modules, but only for the pro and play flavors. */
    private fun String.isAppProFlavor(configuration: String): Boolean =
        this == ":app" && proConfigurationPrefixes.any { configuration.startsWith(it) }
}
