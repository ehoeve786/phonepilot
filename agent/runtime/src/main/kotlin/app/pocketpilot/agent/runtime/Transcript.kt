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
