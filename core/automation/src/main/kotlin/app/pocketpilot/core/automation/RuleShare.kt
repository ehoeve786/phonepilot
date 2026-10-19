package app.pocketpilot.core.automation

/**
 * A rule as plain text for sharing by message or email: its name, then what starts it, what must hold
 * and what it does, one step per line. Ids, run history and the owner's settings are left out.
 */
fun Rule.shareText(): String =
    buildString {
        appendLine(name)
        appendLine()
        appendLine("When:")
        triggers.forEach { appendLine("- ${Describe.trigger(it)}") }
        if (!constraints.isEmpty) {
            appendLine()
            appendLine("Only if:")
            appendLine("- ${Describe.group(constraints)}")
        }
        appendLine()
        appendLine("Then:")
        if (runAs == RunMode.AGENT) {
            appendLine("- Agent task: $agentPrompt")
        } else {
            actions.forEachIndexed { i, step -> appendLine("${i + 1}. ${Describe.action(step)}") }
        }
    }.trimEnd()
