package app.pocketpilot.core.automation

import kotlin.test.Test
import kotlin.test.assertEquals

/** Only commands the owner typed in full are approved with the rule; placeholder commands keep asking. */
class FixedShellCommandsTest {
    private fun shell(command: String) = ActionStep("shell.exec", mapOf("command" to command))

    private fun rule(vararg actions: ActionStep) = Rule(id = "r", name = "r", actions = actions.toList())

    @Test
    fun `typed-out commands are approved, placeholder ones are not`() {
        val fixed = rule(shell("  dumpsys battery  "), shell("echo {trigger.cmd}"))
        assertEquals(setOf("dumpsys battery"), fixed.fixedShellCommands())
    }

    @Test
    fun `commands nested in conditions and loops are found too`() {
        val nested =
            rule(
                ActionStep(
                    type = "flow.if",
                    children = listOf(shell("cmd uimode night yes")),
                    elseChildren = listOf(ActionStep("flow.repeat", children = listOf(shell("cmd uimode night no")))),
                ),
            )
        assertEquals(setOf("cmd uimode night yes", "cmd uimode night no"), nested.fixedShellCommands())
    }

    @Test
    fun `a rule without shell steps approves nothing`() {
        assertEquals(emptySet(), rule(ActionStep("ui.tap_text", mapOf("text" to "OK"))).fixedShellCommands())
    }
}
