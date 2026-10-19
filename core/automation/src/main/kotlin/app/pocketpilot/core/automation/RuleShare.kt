package app.pocketpilot.core.automation

import kotlinx.serialization.Serializable
import java.util.Base64

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

/** The text sent with a share: the readable rule, then a link that adds it to the recipient's PocketPilot. */
fun Rule.shareMessage(): String = shareText() + "\n\nAdd it to PocketPilot: " + shareLink()

/** Who a shared rule is proposed by, as the recipient's Proposals list shows it. */
const val SHARED_RULE_PROPOSER = "a PocketPilot user"

private const val SHARE_PREFIX = "pocketpilot://rule/"

/** The parts of a rule that travel in a [shareLink]; the owner's id, run history and settings stay home. */
@Serializable
private data class SharedRule(
    val name: String,
    val triggers: List<TriggerSpec> = emptyList(),
    val constraints: ConstraintGroup = ConstraintGroup(),
    val actions: List<ActionStep> = emptyList(),
    val runAs: RunMode = RunMode.DIRECT,
    val agentPrompt: String = "",
)

/** A link that opens this rule in another PocketPilot, where it arrives as a proposal the owner can accept. */
fun Rule.shareLink(): String {
    val shared = SharedRule(name, triggers, constraints, actions, runAs, agentPrompt)
    val json = AutomationJson.json.encodeToString(SharedRule.serializer(), shared)
    return SHARE_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())
}

/**
 * The rule in a [shareLink] found in [text], as a disabled proposal from [SHARED_RULE_PROPOSER] with the id
 * from [newId]. Returns null when there is no link or its payload is damaged.
 */
fun parseSharedRule(
    text: String,
    newId: () -> String,
): Rule? {
    val start = text.indexOf(SHARE_PREFIX)
    if (start < 0) return null
    val payload = text.substring(start + SHARE_PREFIX.length).takeWhile { !it.isWhitespace() }
    return runCatching {
        val json = String(Base64.getUrlDecoder().decode(payload))
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
}
