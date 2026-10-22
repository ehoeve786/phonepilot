package app.pocketpilot.core.automation

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One automation: when any of its [triggers] fires and its [constraints] hold, run its [actions] in
 * order. Every trigger, constraint and action names a type from [AutomationCatalog] and holds its
 * settings as plain strings, so the builder, the store and `automation.propose` share one shape.
 */
@Serializable
data class Rule(
    val id: String,
    val name: String,
    /** The group the rule is listed under; blank when it has none. */
    val category: String = "",
    val enabled: Boolean = true,
    val triggers: List<TriggerSpec> = emptyList(),
    val constraints: ConstraintGroup = ConstraintGroup(),
    val actions: List<ActionStep> = emptyList(),
    val runAs: RunMode = RunMode.DIRECT,
    /** For [RunMode.AGENT]: the task Agent mode gets, with the trigger's variables filled in. */
    val agentPrompt: String = "",
    /** Shown on Home with a switch. */
    val pinned: Boolean = false,
    /** Set while the rule is an AI proposal waiting for the owner; cleared when it is accepted. */
    val proposedBy: String? = null,
    /** For a proposal that changes an existing rule: that rule's id. Accepting it updates that rule in place. */
    val replaces: String? = null,
    val createdAtMillis: Long = 0,
    val lastRun: RunSummary? = null,
) {
    val pending: Boolean get() = proposedBy != null
}

/**
 * The rules grouped by their category, each group in name order of its category. Rules with no category
 * come last under "".
 */
fun List<Rule>.byCategory(): List<Pair<String, List<Rule>>> =
    groupBy { it.category.trim() }
        .toList()
        .sortedWith(compareBy<Pair<String, List<Rule>>>({ it.first.isEmpty() }, { it.first.lowercase() }))

/** One trigger of a rule: a [type] from the catalog and its settings. */
@Serializable
data class TriggerSpec(
    val type: String,
    val params: Map<String, String> = emptyMap(),
)

/** One check of state, such as "battery above 20%". */
@Serializable
data class ConstraintSpec(
    val type: String,
    val params: Map<String, String> = emptyMap(),
)

/** How the items of a [ConstraintGroup] combine. */
@Serializable
enum class GroupMode {
    /** Every item holds (AND). */
    ALL,

    /** At least one item holds (OR). */
    ANY,

    /** No item holds (NOT). */
    NONE,

    /** Exactly one item holds (XOR). */
    ONE,
}

/** A constraint, or a nested group of them. */
@Serializable
sealed interface ConstraintNode {
    @Serializable
    @SerialName("check")
    data class Check(
        val spec: ConstraintSpec,
    ) : ConstraintNode

    @Serializable
    @SerialName("group")
    data class Group(
        val group: ConstraintGroup,
    ) : ConstraintNode
}

/** Constraints combined with [mode]; an empty group always holds. */
@Serializable
data class ConstraintGroup(
    val mode: GroupMode = GroupMode.ALL,
    val items: List<ConstraintNode> = emptyList(),
) {
    val isEmpty: Boolean get() = items.isEmpty()

    companion object {
        fun of(vararg checks: ConstraintSpec) = ConstraintGroup(items = checks.map { ConstraintNode.Check(it) })
    }
}

/** What a failed action does to the rest of the run. */
@Serializable
enum class OnError {
    STOP,
    CONTINUE,
}

/**
 * One action. Flow actions ([AutomationCatalog] category FLOW) hold more actions: If runs [children]
 * when [constraints] hold and [elseChildren] otherwise; Repeat and While run [children] in a loop.
 * For any other action, [constraints] decide whether it runs at all.
 */
@Serializable
data class ActionStep(
    val type: String,
    val params: Map<String, String> = emptyMap(),
    val constraints: ConstraintGroup = ConstraintGroup(),
    val onError: OnError = OnError.STOP,
    val children: List<ActionStep> = emptyList(),
    val elseChildren: List<ActionStep> = emptyList(),
)

/** How a rule's actions run. */
@Serializable
enum class RunMode {
    /** The actions, as written. */
    DIRECT,

    /** The trigger's data goes to Agent mode with [Rule.agentPrompt]. */
    AGENT,
}

@Serializable
enum class RunOutcome {
    OK,
    FAILED,
    STOPPED,

    /** A constraint did not hold, or the rule was already running. */
    SKIPPED,
}

/** The last run of a rule, for its card. */
@Serializable
data class RunSummary(
    val atMillis: Long,
    val outcome: RunOutcome,
    val actions: Int,
    val message: String? = null,
)

/** One run of a rule, for the Activity tab. */
@Serializable
data class RunRecord(
    val id: String,
    val ruleId: String,
    val ruleName: String,
    /** What started it: a trigger's title, "Run button", "Test run" or an MCP client. */
    val reason: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val outcome: RunOutcome,
    /** One line per action, as "1. Turn Wi-Fi off: ok". */
    val lines: List<String> = emptyList(),
    /** The run's local variables when it ended, by name. Trigger data and loop counters are left out. */
    val variables: Map<String, String> = emptyMap(),
)

/** Settings that apply to every rule. */
@Serializable
data class AutomationSettings(
    /** Turns every rule off without changing them. */
    val paused: Boolean = false,
    /** The one monthly limit for Agent runs started by automations, in US dollars; null means none. */
    val agentMonthlyCapUsd: Double? = 5.0,
    /** The owner's Home Assistant, such as https://ha.example.com; its token is kept encrypted elsewhere. */
    val homeAssistantUrl: String? = null,
)

/** Everything the store keeps. */
@Serializable
data class AutomationData(
    val rules: List<Rule> = emptyList(),
    /** Global variables, by name. */
    val variables: Map<String, String> = emptyMap(),
    val settings: AutomationSettings = AutomationSettings(),
    /** Newest first. */
    val runs: List<RunRecord> = emptyList(),
    /** Agent spending by automations, by month as "2026-10". */
    val agentSpendUsd: Map<String, Double> = emptyMap(),
)

/** Something that happened on the phone: [type] is a trigger type, [data] its details. */
data class TriggerEvent(
    val type: String,
    val data: Map<String, String> = emptyMap(),
    /** Set when the event is meant for one rule only, such as its own alarm. */
    val ruleId: String? = null,
    /** Set when the event came from a run another app started, so the rules it starts get none of the owner's pre-approvals. */
    val fromOutside: Boolean = false,
    /** For a rule.finished event: the rules whose finishing led to this one, so a loop of rules stops. */
    val chain: List<String> = emptyList(),
)

/**
 * The shell commands a rule types out in full. The owner approves them with the rule, so they run
 * without a prompt each time. A command with a placeholder can take its text from an event's extras or
 * a screen, so it still asks for each command.
 */
fun Rule.fixedShellCommands(): Set<String> =
    shellSteps()
        .map { it.params["command"].orEmpty().trim() }
        .filter { it.isNotEmpty() && !Placeholders.contains(it) }
        .toSet()

/** Every shell command a rule runs, as written, fixed or with placeholders. */
fun Rule.shellCommands(): List<String> = shellSteps().map { it.params["command"].orEmpty().trim() }

/** True when any step runs a shell command, fixed or built from a placeholder. */
fun Rule.usesShell(): Boolean = shellSteps().isNotEmpty()

private fun Rule.shellSteps(): List<ActionStep> {
    fun ActionStep.all(): List<ActionStep> = listOf(this) + children.flatMap { it.all() } + elseChildren.flatMap { it.all() }
    return actions.flatMap { it.all() }.filter { it.type == "shell.exec" }
}
