package app.pocketpilot.agent.runtime

import app.pocketpilot.agent.providers.api.Message
import app.pocketpilot.agent.providers.api.Part

/**
 * Keeps requests small as a run grows: every action returns the whole screen, and old screens are
 * not worth resending. Long texts in tool results older than the last [KEEP_RECENT] result messages
 * are cut to their start. The first message, which holds the task, is never cut.
 */
internal object TextBudget {
    const val KEEP_RECENT = 2
    const val LONG = 600
    const val KEEP_CHARS = 240
    const val MARKER = " … [older screen removed]"

    fun apply(messages: List<Message>): List<Message> {
        val resultMessages = messages.indices.filter { i -> messages[i].parts.any { it is Part.ToolResult } || isJsonResult(messages[i]) }
        val cutBefore = resultMessages.dropLast(KEEP_RECENT).toSet()
        return messages.mapIndexed { i, message ->
            if (i == 0 || i !in cutBefore) message else message.copy(parts = message.parts.map(::cut))
        }
    }

    /** JSON fallback results travel as user text starting with "Result of". */
    private fun isJsonResult(message: Message): Boolean = (message.parts.firstOrNull() as? Part.Text)?.text?.startsWith(JSON_RESULT) == true

    private fun cut(part: Part): Part =
        when (part) {
            is Part.Text -> if (part.text.length > LONG) Part.Text(part.text.take(KEEP_CHARS) + MARKER) else part
            is Part.ToolResult -> part.copy(parts = part.parts.map(::cut))
            else -> part
        }

    const val JSON_RESULT = "Result of "
}
