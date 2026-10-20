package app.pocketpilot.core.automation

import app.pocketpilot.core.common.Clock
import app.pocketpilot.core.common.UlidGenerator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** Where the store keeps its JSON. */
interface Persistence {
    fun load(): String?

    fun save(text: String)
}

/** Writes through a temporary file, so a crash mid-write never leaves half a file. */
class FilePersistence(
    private val file: File,
) : Persistence {
    override fun load(): String? = file.takeIf { it.exists() }?.readText()

    override fun save(text: String) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(text)
        if (!temp.renameTo(file)) {
            file.writeText(text)
            temp.delete()
        }
    }
}

/** Keeps rules, global variables, settings and the run history, and saves every change at once. */
class RuleStore(
    private val persistence: Persistence,
    private val clock: Clock = Clock.System,
    private val ids: UlidGenerator = UlidGenerator(clock),
) {
    private val state = MutableStateFlow(load())

    val data: StateFlow<AutomationData> = state.asStateFlow()

    val rules: List<Rule> get() = state.value.rules

    fun rule(id: String): Rule? = state.value.rules.firstOrNull { it.id == id }

    fun newId(): String = ids.next()

    @Synchronized
    fun update(transform: (AutomationData) -> AutomationData) {
        val next = transform(state.value)
        if (next == state.value) return
        state.value = next
        persistence.save(AutomationJson.json.encodeToString(AutomationData.serializer(), next))
    }

    /** Adds [rule], or replaces the rule with its id. */
    fun upsert(rule: Rule) =
        update { data ->
            val stamped = if (rule.createdAtMillis == 0L) rule.copy(createdAtMillis = clock.nowMillis()) else rule
            val index = data.rules.indexOfFirst { it.id == rule.id }
            data.copy(rules = if (index < 0) data.rules + stamped else data.rules.toMutableList().also { it[index] = stamped })
        }

    fun delete(id: String) = update { data -> data.copy(rules = data.rules.filterNot { it.id == id }) }

    fun setEnabled(
        id: String,
        enabled: Boolean,
    ) = edit(id) { if (it.pending) it else it.copy(enabled = enabled) }

    fun setPinned(
        id: String,
        pinned: Boolean,
    ) = edit(id) { it.copy(pinned = pinned) }

    /** The owner accepted a proposal: it becomes an ordinary rule and loses its "Proposed by" label. */
    fun accept(id: String) {
        rule(id)?.let { saveAccepted(it) }
    }

    /**
     * Saves an accepted proposal, perhaps edited by the owner first. One that changes an existing rule
     * updates that rule in place and goes away; any other becomes an ordinary rule.
     */
    fun saveAccepted(proposal: Rule) {
        val accepted = proposal.copy(proposedBy = null, enabled = true, replaces = null)
        val target = proposal.replaces?.let(::rule)
        if (target == null) return upsert(accepted)
        update { data ->
            val updated = target.changedTo(accepted).copy(enabled = true)
            data.copy(rules = data.rules.filterNot { it.id == proposal.id }.map { if (it.id == target.id) updated else it })
        }
    }

    /** Changes rule [id] in place to [changed], keeping its id, switch, pin, creation time and last run. */
    fun change(
        id: String,
        changed: Rule,
    ) = edit(id) { it.changedTo(changed) }

    fun setVariable(
        name: String,
        value: String?,
    ) = update { data -> data.copy(variables = if (value == null) data.variables - name else data.variables + (name to value)) }

    fun setSettings(settings: AutomationSettings) = update { it.copy(settings = settings) }

    /** Saves a finished run, keeping the newest [MAX_RUNS] and at most [MAX_HISTORY_CHARS] of their log lines. */
    fun addRun(record: RunRecord) =
        update { data ->
            data.copy(
                runs = keptRuns(listOf(record) + data.runs),
                rules =
                    data.rules.map {
                        if (it.id != record.ruleId || record.outcome == RunOutcome.SKIPPED) {
                            it
                        } else {
                            it.copy(
                                lastRun = RunSummary(record.endedAtMillis, record.outcome, record.lines.size, record.lines.lastOrNull()),
                            )
                        }
                    },
            )
        }

    fun addAgentSpend(
        month: String,
        usd: Double,
    ) = update { data -> data.copy(agentSpendUsd = data.agentSpendUsd + (month to (data.agentSpendUsd[month] ?: 0.0) + usd)) }

    private fun Rule.changedTo(changed: Rule) =
        changed.copy(
            id = id,
            enabled = enabled,
            pinned = pinned,
            proposedBy = proposedBy,
            replaces = replaces,
            createdAtMillis = createdAtMillis,
            lastRun = lastRun,
        )

    private fun edit(
        id: String,
        change: (Rule) -> Rule,
    ) = update { data -> data.copy(rules = data.rules.map { if (it.id == id) change(it) else it }) }

    private fun load(): AutomationData =
        persistence
            .load()
            ?.let { runCatching { AutomationJson.json.decodeFromString(AutomationData.serializer(), it) }.getOrNull() }
            ?: AutomationData()

    private fun keptRuns(runs: List<RunRecord>): List<RunRecord> {
        var total = 0
        return runs.take(MAX_RUNS).takeWhile { run ->
            total += run.lines.sumOf { it.length }
            total <= MAX_HISTORY_CHARS
        }
    }

    companion object {
        const val MAX_RUNS = 200
        const val MAX_HISTORY_CHARS = 256 * 1024
    }
}
