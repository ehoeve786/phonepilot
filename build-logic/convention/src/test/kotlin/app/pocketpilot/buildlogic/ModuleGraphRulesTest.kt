package app.pocketpilot.buildlogic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModuleGraphRulesTest {
    private fun violations(
        vararg edges: Pair<String, String>,
        configuration: String = "implementation",
    ) = ModuleGraphRules.violations(edges.map { (from, to) -> ModuleEdge(from, to, configuration) })

    @Test
    fun `allowed graph has no violations`() {
        val result =
            violations(
                ":app" to ":core:model",
                ":app" to ":capability:accessibility",
                ":capability:accessibility" to ":capability:api",
                ":capability:accessibility" to ":core:common",
                ":core:orchestrator" to ":capability:api",
                ":core:orchestrator" to ":core:model",
                ":server:mcp" to ":core:orchestrator",
                ":feature:home" to ":core:designsystem",
            )
        assertEquals(emptyList(), result)
    }

    @Test
    fun `pure kotlin modules cannot depend on anything`() {
        assertTrue(violations(":core:model" to ":core:common").single().contains("core/model and core/common"))
    }

    @Test
    fun `capability cannot depend on another capability`() {
        assertTrue(violations(":capability:shizuku" to ":capability:accessibility").isNotEmpty())
    }

    @Test
    fun `server cannot call a capability directly`() {
        assertTrue(violations(":server:mcp" to ":capability:api").single().contains("core/orchestrator"))
    }

    @Test
    fun `features cannot depend on each other`() {
        assertTrue(violations(":feature:home" to ":feature:doctor").isNotEmpty())
    }

    @Test
    fun `core cannot depend upward`() {
        assertTrue(violations(":core:policy" to ":server:mcp").isNotEmpty())
    }

    @Test
    fun `open modules cannot depend on pro, except the app's pro and play flavors`() {
        assertTrue(violations(":core:policy" to ":pro:licensing").isNotEmpty())
        assertTrue(violations(":app" to ":pro:licensing").isNotEmpty())
        assertEquals(emptyList(), violations(":app" to ":pro:licensing", configuration = "proImplementation"))
        assertEquals(emptyList(), violations(":app" to ":pro:licensing", configuration = "playImplementation"))
    }

    @Test
    fun `nothing depends on the app`() {
        assertTrue(violations(":feature:home" to ":app").isNotEmpty())
    }
}
