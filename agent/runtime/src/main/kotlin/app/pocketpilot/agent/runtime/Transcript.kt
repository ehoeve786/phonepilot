package app.pocketpilot.agent.runtime

/** One line per step, for the run view: the model's words, then each call and how it went. */
fun stepLine(step: AgentStep): String =
    buildString {
        append(step.index + 1).append(". ")
        step.note?.let { append("· ").append(it) }
        step.text?.let { append(it.lineSequence().first().take(LINE_TEXT)) }
        for (call in step.calls) {
            if (isNotEmpty() && !endsWith(" ")) append("\n   ")
            append(if (call.isError) "✗ " else "✓ ").append(call.tool)
            if (call.argsJson.isNotBlank() && call.argsJson != "{}") append(' ').append(call.argsJson.take(LINE_ARGS))
            if (call.isError) {
                append(" — ").append(
                    call.resultSummary
                        .lineSequence()
                        .first()
                        .take(LINE_TEXT),
                )
            }
        }
    }

/** The whole run as plain text, for copying into a bug report. */
fun transcript(run: RunState): String =
    buildString {
        appendLine("Goal: ${run.goal}")
        appendLine("Model: ${run.provider} / ${run.model}")
        appendLine("Status: ${run.status}${run.outcome?.let { " — $it" } ?: ""}")
        appendLine("Tokens: ${run.inputTokens} in, ${run.outputTokens} out${run.costUsd?.let { ", about $%.4f".format(it) } ?: ""}")
        for (step in run.steps) {
            appendLine()
            appendLine("Step ${step.index + 1}${step.screenHash?.let { " (screen $it)" } ?: ""}")
            step.note?.let { appendLine("Note: $it") }
            step.text?.let { appendLine(it) }
            for (call in step.calls) {
                appendLine("${if (call.isError) "FAILED" else "Called"} ${call.tool} ${call.argsJson}")
                appendLine("  → ${call.resultSummary}")
            }
        }
    }

private const val LINE_TEXT = 120
private const val LINE_ARGS = 80

/** One entry of a chat as the Chat screen shows it. */
sealed interface ChatItem {
    data class Owner(
        val text: String,
    ) : ChatItem

    data class Agent(
        val text: String,
    ) : ChatItem

    /** The agent's work between two messages: its tool calls, thoughts and retries. */
    data class Work(
        val steps: List<AgentStep>,
    ) : ChatItem

    /** Why a turn or the chat ended without an answer, such as a budget or a failure. */
    data class Note(
        val text: String,
    ) : ChatItem
}

/** A chat's messages in order, with each stretch of work between them as one [ChatItem.Work]. */
fun chatItems(run: RunState): List<ChatItem> =
    buildList {
        add(ChatItem.Owner(run.goal))
        val work = mutableListOf<AgentStep>()

        fun flush() {
            if (work.isNotEmpty()) add(ChatItem.Work(work.toList()))
            work.clear()
        }
        run.steps.forEachIndexed { i, step ->
            val next = run.steps.getOrNull(i + 1)
            // A turn that ran out records its reason as a note right before the owner's next message.
            val turnEnd = step.note != null && (next?.user != null || (next == null && run.status == RunStatus.WAITING))
            when {
                step.user != null -> {
                    flush()
                    add(ChatItem.Owner(step.user))
                }

                step.reply != null -> {
                    flush()
                    add(ChatItem.Agent(step.reply))
                }

                turnEnd && step.calls.isEmpty() && step.text == null -> {
                    flush()
                    add(ChatItem.Note(step.note))
                }

                else -> {
                    work += step
                }
            }
        }
        flush()
        val outcome = run.outcome
        if (outcome != null && run.status in ENDED_WITHOUT_ANSWER && (last() as? ChatItem.Note)?.text != outcome) {
            add(ChatItem.Note(outcome))
        }
    }

private val ENDED_WITHOUT_ANSWER = setOf(RunStatus.STOPPED, RunStatus.OUT_OF_BUDGET, RunStatus.FAILED)
