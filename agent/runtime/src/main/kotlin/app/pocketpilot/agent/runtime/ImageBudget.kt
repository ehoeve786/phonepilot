package app.pocketpilot.agent.runtime

import app.pocketpilot.agent.providers.api.Message
import app.pocketpilot.agent.providers.api.Part

/**
 * Keeps requests small (spec section 9, Observe): only the last two messages that carry images keep
 * them, and at most `maxImages` of the newest. Older screenshots become a short placeholder so the
 * model still knows one was there.
 */
internal object ImageBudget {
    const val PLACEHOLDER = "[older screenshot removed]"
    private const val IMAGE_MESSAGES = 2

    fun apply(
        messages: List<Message>,
        maxImages: Int,
    ): List<Message> {
        var messagesLeft = IMAGE_MESSAGES
        var imagesLeft = maxImages
        val out = ArrayList<Message>(messages.size)
        for (message in messages.asReversed()) {
            if (!message.parts.any(::hasImage)) {
                out += message
                continue
            }
            val keepHere = messagesLeft > 0
            messagesLeft--
            // Walk parts newest first so the latest images win the budget.
            val parts =
                message.parts
                    .asReversed()
                    .map { part ->
                        trim(part) {
                            if (keepHere && imagesLeft > 0) {
                                imagesLeft--
                                true
                            } else {
                                false
                            }
                        }
                    }.asReversed()
            out += message.copy(parts = parts)
        }
        return out.asReversed()
    }

    private fun hasImage(part: Part): Boolean =
        when (part) {
            is Part.Image -> true
            is Part.ToolResult -> part.parts.any(::hasImage)
            else -> false
        }

    private fun trim(
        part: Part,
        keep: () -> Boolean,
    ): Part =
        when (part) {
            is Part.Image -> {
                if (keep()) part else Part.Text(PLACEHOLDER)
            }

            is Part.ToolResult -> {
                part.copy(
                    parts =
                        part.parts
                            .asReversed()
                            .map { trim(it, keep) }
                            .asReversed(),
                )
            }

            else -> {
                part
            }
        }
}
