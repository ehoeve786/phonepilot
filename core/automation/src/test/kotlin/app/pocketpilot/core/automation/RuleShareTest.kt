package app.pocketpilot.core.automation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RuleShareTest {
    private val rule =
        Rule(
            id = "r1",
            name = "Morning",
            triggers = listOf(TriggerSpec("no.such.trigger")),
            constraints = ConstraintGroup.of(ConstraintSpec("no.such.condition")),
            actions = listOf(ActionStep("no.such.action")),
        )

    @Test
    fun `a shared rule lists its name, when, only if and then in plain words`() {
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
        val withHistory = rule.copy(lastRun = RunSummary(atMillis = 1, outcome = RunOutcome.OK, actions = 1))

        val json = withHistory.shareFileJson()
        val received = parseSharedRule(json) { "new-id" }!!

        assertFalse(json.contains("lastRun"))
        assertFalse(json.contains("\"OK\""))
        assertFalse(withHistory.shareText().contains("OK"))
        assertEquals("new-id", received.id)
        assertNull(received.lastRun)
    }

    @Test
    fun `an agent rule shares its prompt as its only step`() {
        val agent = rule.copy(runAs = RunMode.AGENT, agentPrompt = "Check the mail", actions = emptyList())

        assertTrue(agent.shareText().endsWith("Then:\n- Agent task: Check the mail"))
        assertEquals("Check the mail", parseSharedRule(agent.shareFileJson()) { "x" }!!.agentPrompt)
    }

    @Test
    fun `a shared rule with a blank name is not a rule`() {
        assertNull(parseSharedRule(rule.copy(name = "  ").shareFileJson()) { "x" })
    }

    @Test
    fun `a shared file opens as a disabled proposal with the same steps`() {
        val received = parseSharedRule(rule.shareFileJson()) { "new-id" }!!

        assertEquals("new-id", received.id)
        assertEquals("Morning", received.name)
        assertEquals(rule.triggers, received.triggers)
        assertEquals(rule.constraints, received.constraints)
        assertEquals(rule.actions, received.actions)
        assertEquals(SHARED_RULE_PROPOSER, received.proposedBy)
        assertFalse(received.enabled)
        assertTrue(received.pending)
    }

    @Test
    fun `text that is not a shared rule is not a rule`() {
        assertNull(parseSharedRule("hello there") { "x" })
        assertNull(parseSharedRule("{\"unrelated\":true}") { "x" })
    }
}
