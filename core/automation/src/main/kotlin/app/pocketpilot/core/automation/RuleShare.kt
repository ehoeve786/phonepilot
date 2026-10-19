package app.pocketpilot.core.automation

import kotlinx.serialization.Serializable

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

/** Who a shared rule is proposed by, as the recipient's Proposals list shows it. */
const val SHARED_RULE_PROPOSER = "a PocketPilot user"

/** The MIME type of a shared rule file, which PocketPilot registers to open. */
const val SHARED_RULE_MIME_TYPE = "application/vnd.pocketpilot.rule+json"

/** The parts of a rule that travel in a shared file; the owner's id, run history and settings stay home. */
@Serializable
private data class SharedRule(
    val name: String,
    val triggers: List<TriggerSpec> = emptyList(),
    val constraints: ConstraintGroup = ConstraintGroup(),
    val actions: List<ActionStep> = emptyList(),
    val runAs: RunMode = RunMode.DIRECT,
    val agentPrompt: String = "",
)

/** The rule as the JSON text of a shared file. */
fun Rule.shareFileJson(): String {
    val shared = SharedRule(name, triggers, constraints, actions, runAs, agentPrompt)
    return AutomationJson.json.encodeToString(SharedRule.serializer(), shared)
}

/**
 * The rule in a shared file's [json], as a disabled proposal from [SHARED_RULE_PROPOSER] with the id from
 * [newId]. Returns null when the file is not a rule.
 */
fun parseSharedRule(
    json: String,
    newId: () -> String,
): Rule? =
    runCatching {
        val shared = AutomationJson.json.decodeFromString(SharedRule.serializer(), json)
        Rule(
            id = newId(),
            name = shared.name.trim(),
            enabled = false,
            triggers = shared.triggers,
            constraints = shared.constraints,
            actions = shared.actions,
            runAs = shared.runAs,
            agentPrompt = shared.agentPrompt,
            proposedBy = SHARED_RULE_PROPOSER,
        )
    }.getOrNull()
