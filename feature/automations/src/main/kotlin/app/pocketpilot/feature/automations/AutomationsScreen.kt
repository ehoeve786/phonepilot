package app.pocketpilot.feature.automations

import android.content.Context
import android.graphics.Bitmap
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.pocketpilot.core.automation.AutomationJson
import app.pocketpilot.core.automation.AutomationSettings
import app.pocketpilot.core.automation.PickOption
import app.pocketpilot.core.automation.PickSource
import app.pocketpilot.core.automation.Rule
import app.pocketpilot.core.automation.RunOutcome
import app.pocketpilot.core.automation.RunRecord
import app.pocketpilot.core.automation.Templates
import app.pocketpilot.designsystem.LiquidGlassBentoCard
import app.pocketpilot.core.designsystem.R as DsR

/** An installed app, for the app picker. */
data class AppOption(
    val packageName: String,
    val label: String,
    val icon: Bitmap? = null,
)

/** Everything the Automations tab shows. */
data class AutomationsState(
    val rules: List<Rule> = emptyList(),
    val runs: List<RunRecord> = emptyList(),
    /** Global variables, by name. */
    val variables: Map<String, String> = emptyMap(),
    val running: Set<String> = emptySet(),
    val settings: AutomationSettings = AutomationSettings(),
    val agentSpentThisMonthUsd: Double = 0.0,
    val apps: List<AppOption> = emptyList(),
    val options: Map<PickSource, List<PickOption>> = emptyMap(),
    /** Tool names for "Call any tool". */
    val tools: List<String> = emptyList(),
    /** False in the Play build: no shell actions. */
    val sideload: Boolean = true,
    /** A Home Assistant access token is saved. */
    val homeAssistantToken: Boolean = false,
)

/** What the Automations tab can ask for. */
class AutomationsActions(
    val newId: () -> String,
    val save: (Rule) -> Unit,
    val delete: (String) -> Unit,
    val setEnabled: (String, Boolean) -> Unit,
    val setPinned: (String, Boolean) -> Unit,
    /** Runs the rule now, checking its conditions. */
    val run: (Rule) -> Unit,
    /** Puts a home screen icon named [name] on the home, for the rule's "Home screen icon" trigger. */
    val addIcon: (String) -> Unit,
    /** Runs the rule's actions once now, without its conditions. */
    val test: (Rule) -> Unit,
    val stop: (String) -> Unit,
    val accept: (String) -> Unit,
    val decline: (String) -> Unit,
    val setSettings: (AutomationSettings) -> Unit,
    /** Sets a global variable, or removes it when the value is null. */
    val setVariable: (String, String?) -> Unit = { _, _ -> },
    /** Loads [AutomationsState.apps] the first time a picker needs them. */
    val loadApps: () -> Unit,
    /** Loads the suggested values for one kind of text field the first time its list is needed. */
    val loadOptions: (PickSource) -> Unit = {},
    /** The entries of a folder for a path field's file browser. */
    val listDirectory: suspend (String) -> List<PickOption> = { emptyList() },
    /** Saves Home Assistant's address, and its token when one is typed. */
    val setHomeAssistant: (url: String, token: String) -> Unit = { _, _ -> },
)

private val RuleSaver =
    Saver<Rule?, String>(
        save = { rule -> rule?.let { AutomationJson.json.encodeToString(Rule.serializer(), it) } ?: "" },
        restore = { text -> text.takeIf { it.isNotEmpty() }?.let { AutomationJson.json.decodeFromString(Rule.serializer(), it) } },
    )

/**
 * The Automations tab: Rules, Proposals from AI apps and the run History, with the full-screen builder
 * on top when a rule is open. [openProposals] switches to Proposals, for a proposal notification.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutomationsScreen(
    state: AutomationsState,
    actions: AutomationsActions,
    modifier: Modifier = Modifier,
    openProposals: Boolean = false,
    onProposalsOpened: () -> Unit = {},
) {
    var editing by rememberSaveable(stateSaver = RuleSaver) { mutableStateOf<Rule?>(null) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(openProposals) {
        if (openProposals) {
            tab = 1
            onProposalsOpened()
        }
    }
    val current = editing
    if (current != null) {
        BackHandler { editing = null }
        val existing = state.rules.any { it.id == current.id }
        RuleEditor(
            initial = current,
            state = state,
            isNew = !existing,
            onSave = { rule ->
                // Saving a proposal accepts it: it becomes an ordinary rule without the "Proposed by" label.
                actions.save(if (rule.pending) rule.copy(proposedBy = null, enabled = true) else rule)
                editing = null
            },
            onClose = { editing = null },
            onTest = actions.test,
            onDelete =
                if (existing) {
                    {
                        actions.delete(current.id)
                        editing = null
                    }
                } else {
                    null
                },
            onLoadApps = actions.loadApps,
            onLoadOptions = actions.loadOptions,
            onListDirectory = actions.listDirectory,
            modifier = modifier,
        )
        return
    }

    val rules = state.rules.filterNot { it.pending }
    val proposals = state.rules.filter { it.pending }
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            SecondaryTabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.feature_automations_tab_rules)) })
                Tab(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    text = {
                        if (proposals.isEmpty()) {
                            Text(stringResource(R.string.feature_automations_tab_proposals))
                        } else {
                            BadgedBox(badge = { Badge { Text(proposals.size.toString()) } }) {
                                Text(stringResource(R.string.feature_automations_tab_proposals))
                            }
                        }
                    },
                )
                Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text(stringResource(R.string.feature_automations_tab_history)) })
                Tab(selected = tab == 3, onClick = { tab = 3 }, text = { Text(stringResource(R.string.feature_automations_tab_variables)) })
            }
            when (tab) {
                0 -> {
                    RulesList(
                        rules = rules,
                        state = state,
                        actions = actions,
                        onOpen = { editing = it },
                        onNewFrom = { template -> editing = template.copy(id = actions.newId()) },
                    )
                }

                1 -> {
                    ProposalsList(proposals, state.rules, actions, onEdit = { editing = it })
                }

                2 -> {
                    HistoryList(state.runs)
                }

                else -> {
                    VariablesList(state.variables, actions.setVariable)
                }
            }
        }
        if (tab == 0) {
            ExtendedFloatingActionButton(
                onClick = { editing = Rule(id = actions.newId(), name = "") },
                icon = { Icon(painterResource(R.drawable.feature_automations_ic_add), contentDescription = null) },
                text = { Text(stringResource(R.string.feature_automations_new_rule)) },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            )
        }
    }
}

@Composable
private fun RulesList(
    rules: List<Rule>,
    state: AutomationsState,
    actions: AutomationsActions,
    onOpen: (Rule) -> Unit,
    onNewFrom: (Rule) -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (rules.isEmpty()) {
            item {
                Text(stringResource(R.string.feature_automations_empty), style = MaterialTheme.typography.bodyLarge)
            }
            item {
                Text(stringResource(R.string.feature_automations_templates), style = MaterialTheme.typography.titleSmall)
            }
            items(Templates.all, key = { "t" + it.id }) { template ->
                LiquidGlassBentoCard(
                    onClick = { onNewFrom(template) },
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(template.name, style = MaterialTheme.typography.titleMedium)
                        Text(Templates.summary(template), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        items(rules, key = { it.id }) { rule ->
            AutomationRuleCard(
                rule = rule,
                running = rule.id in state.running,
                onEnabledChange = { actions.setEnabled(rule.id, it) },
                onOpen = { onOpen(rule) },
                onRun = { actions.run(rule) },
                onAddIcon = actions.addIcon,
                onStop = { actions.stop(rule.id) },
                onPinnedChange = { actions.setPinned(rule.id, it) },
            )
        }
        if (rules.isNotEmpty()) {
            item { Spacer(Modifier.height(8.dp)) }
            item { Text(stringResource(R.string.feature_automations_templates), style = MaterialTheme.typography.titleSmall) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Templates.all.take(2).forEach { template ->
                        OutlinedButton(onClick = { onNewFrom(template) }) { Text(template.name) }
                    }
                }
            }
        }
        item { SettingsCard(state, actions) }
    }
}

@Composable
private fun SettingsCard(
    state: AutomationsState,
    actions: AutomationsActions,
) {
    var cap by rememberSaveable(state.settings.agentMonthlyCapUsd) {
        mutableStateOf(state.settings.agentMonthlyCapUsd?.let { "%.2f".format(it) } ?: "")
    }
    LiquidGlassBentoCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.feature_automations_pause_all), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.feature_automations_pause_all_text), style = MaterialTheme.typography.bodyMedium)
                }
                Switch(
                    checked = state.settings.paused,
                    onCheckedChange = { actions.setSettings(state.settings.copy(paused = it)) },
                )
            }
            Text(stringResource(R.string.feature_automations_ai_cap), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.feature_automations_ai_cap_text, "%.2f".format(state.agentSpentThisMonthUsd)),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = cap,
                    onValueChange = { cap = it },
                    label = { Text(stringResource(R.string.feature_automations_ai_cap_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { actions.setSettings(state.settings.copy(agentMonthlyCapUsd = cap.trim().toDoubleOrNull())) },
                ) { Text(stringResource(R.string.feature_automations_save)) }
            }
            HomeAssistantSettings(state, actions)
        }
    }
}

@Composable
private fun HomeAssistantSettings(
    state: AutomationsState,
    actions: AutomationsActions,
) {
    var url by rememberSaveable(state.settings.homeAssistantUrl) { mutableStateOf(state.settings.homeAssistantUrl.orEmpty()) }
    var token by rememberSaveable { mutableStateOf("") }
    Text(stringResource(R.string.feature_automations_ha), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.feature_automations_ha_text), style = MaterialTheme.typography.bodyMedium)
    OutlinedTextField(
        value = url,
        onValueChange = { url = it },
        label = { Text(stringResource(R.string.feature_automations_ha_url)) },
        placeholder = { Text("https://homeassistant.local:8123") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text(stringResource(R.string.feature_automations_ha_token)) },
            placeholder = {
                if (state.homeAssistantToken) Text(stringResource(R.string.feature_automations_ha_token_saved))
            },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = {
                actions.setHomeAssistant(url.trim(), token.trim())
                token = ""
            },
        ) { Text(stringResource(R.string.feature_automations_save)) }
    }
}

@Composable
private fun ProposalsList(
    proposals: List<Rule>,
    rules: List<Rule>,
    actions: AutomationsActions,
    onEdit: (Rule) -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (proposals.isEmpty()) {
            item { Text(stringResource(R.string.feature_automations_no_proposals), style = MaterialTheme.typography.bodyLarge) }
        }
        items(proposals, key = { it.id }) { rule ->
            ProposalCard(
                rule = rule,
                replacesName = rule.replaces?.let { id -> rules.firstOrNull { it.id == id }?.name },
                onAccept = { actions.accept(rule.id) },
                onEdit = { onEdit(rule) },
                onDecline = { actions.decline(rule.id) },
            )
        }
    }
}

@Composable
private fun HistoryList(runs: List<RunRecord>) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (runs.isEmpty()) {
            item { Text(stringResource(R.string.feature_automations_no_runs), style = MaterialTheme.typography.bodyLarge) }
        }
        items(runs, key = { it.id }) { run -> RunCard(run) }
    }
}

/** A run as plain text for the clipboard: its rule, when and outcome, then one line per log line. */
private fun runLogText(
    run: RunRecord,
    context: Context,
): String =
    buildString {
        appendLine("${run.ruleName} · ${run.reason}")
        appendLine(DateUtils.formatDateTime(context, run.startedAtMillis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME))
        appendLine(outcomeLabel(run.outcome))
        run.lines.forEach { appendLine(it) }
    }

@Composable
private fun RunCard(run: RunRecord) {
    var open by rememberSaveable(run.id) { mutableStateOf(false) }
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val failed = run.outcome == RunOutcome.FAILED
    LiquidGlassBentoCard(
        onClick = { open = !open },
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(0.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(run.ruleName, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                // A failed run is marked in the error colour, since the glass card has no tinted state.
                Text(
                    outcomeLabel(run.outcome),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                "${run.reason} · ${DateUtils.getRelativeTimeSpanString(run.startedAtMillis)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (open) {
                run.lines.forEach { line ->
                    Text(line, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    IconButton(onClick = { clipboard.setText(AnnotatedString(runLogText(run, context))) }) {
                        Icon(
                            painterResource(DsR.drawable.core_designsystem_ic_copy),
                            contentDescription = stringResource(R.string.feature_automations_copy_log),
                            tint = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun outcomeLabel(outcome: RunOutcome): String =
    stringResource(
        when (outcome) {
            RunOutcome.OK -> R.string.feature_automations_outcome_ok
            RunOutcome.FAILED -> R.string.feature_automations_outcome_failed
            RunOutcome.STOPPED -> R.string.feature_automations_outcome_stopped
            RunOutcome.SKIPPED -> R.string.feature_automations_outcome_skipped
        },
    )

@Composable
internal fun SaveAndTestRow(
    onTest: () -> Unit,
    onSave: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onTest) {
            Icon(painterResource(R.drawable.feature_automations_ic_play), contentDescription = null)
            Text(stringResource(R.string.feature_automations_test_run), modifier = Modifier.padding(start = 4.dp))
        }
        Button(onClick = onSave) { Text(stringResource(R.string.feature_automations_save)) }
    }
}
