package app.pocketpilot.core.automation

import kotlin.test.Test
import kotlin.test.assertEquals

class RuleCategoryTest {
    private fun rule(
        id: String,
        category: String,
    ) = Rule(id = id, name = id, category = category)

    @Test
    fun `rules group by category in name order with uncategorized last`() {
        val groups =
            listOf(rule("a", ""), rule("b", "Tesla"), rule("c", "dasher "), rule("d", "Tesla"), rule("e", "Uber"))
                .byCategory()

        assertEquals(listOf("dasher", "Tesla", "Uber", ""), groups.map { it.first })
        assertEquals(listOf("b", "d"), groups.first { it.first == "Tesla" }.second.map { it.id })
    }
}
