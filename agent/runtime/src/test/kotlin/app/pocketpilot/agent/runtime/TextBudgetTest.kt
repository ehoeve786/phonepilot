package app.pocketpilot.agent.runtime

import app.pocketpilot.agent.providers.api.Message
import app.pocketpilot.agent.providers.api.Part
import app.pocketpilot.agent.providers.api.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextBudgetTest {
    private val screen = "Screen now: " + "x".repeat(5_000)

    private fun result(n: Int) = Message(Role.USER, listOf(Part.ToolResult("c$n", "ui_tap", listOf(Part.Text(screen)))))

    private fun call(n: Int) = Message(Role.ASSISTANT, listOf(Part.ToolCall("c$n", "ui_tap", "{}")))

    private fun text(message: Message): String = ((message.parts.single() as Part.ToolResult).parts.single() as Part.Text).text

    @Test
    fun `cuts old screens and keeps the task and the last result whole`() {
        val first = Message(Role.USER, listOf(Part.Text("Task: switch to light mode"), Part.Text("Current screen snapshot: $screen")))
        val messages = listOf(first) + (1..4).flatMap { listOf(call(it), result(it)) }
        val out = TextBudget.apply(messages)

        assertEquals(first.parts.first(), out.first().parts.first())
        assertTrue((out.first().parts[1] as Part.Text).text.endsWith(TextBudget.MARKER))
        val results = out.filter { m -> m.parts.any { it is Part.ToolResult } }
        assertTrue(results.dropLast(1).all { text(it).endsWith(TextBudget.MARKER) && text(it).length < 300 })
        assertEquals(screen, text(results.last()))
    }

    @Test
    fun `cuts old JSON fallback results too`() {
        val old = Message(Role.USER, listOf(Part.Text("${TextBudget.JSON_RESULT}ui.tap:"), Part.Text(screen)))
        val messages =
            listOf(Message.user("Task"), old, Message.user("${TextBudget.JSON_RESULT}a:"))
        assertTrue((TextBudget.apply(messages)[1].parts[1] as Part.Text).text.endsWith(TextBudget.MARKER))
    }
}
