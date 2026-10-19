package app.pocketpilot.core.automation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class RuleShareTest {
    @Test
    fun `a shared rule lists its name, when, only if and then in plain words`() {
        val rule =
            Rule(
                id = "r1",
                name = "Morning",
                triggers = listOf(TriggerSpec("no.such.trigger")),
                constraints = ConstraintGroup.of(ConstraintSpec("no.such.condition")),
                actions = listOf(ActionStep("no.such.action")),
            )

        assertEquals(
            """
            Morning

            When:
            - Unknown trigger no.such.trigger

            Only if:
            - Unknown condition no.such.condition

            Then:
            1. Unknown action no.such.action
            """.trimIndent(),
            rule.shareText(),
        )
    }

    @Test
    fun `run history and the rule id are not shared`() {
        val rule =
            Rule(
                id = "r1",
                name = "Morning",
                actions = listOf(ActionStep("no.such.action")),
                lastRun = RunSummary(atMillis = 1, outcome = RunOutcome.OK, actions = 1),
            )

        val text = rule.shareText()

        assertFalse(text.contains("r1"))
        assertFalse(text.contains("OK"))
    }
}
