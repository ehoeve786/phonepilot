package app.pocketpilot.feature.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.pocketpilot.agent.runtime.AgentProfile
import app.pocketpilot.agent.runtime.ProviderKind
import app.pocketpilot.agent.runtime.RunState
import app.pocketpilot.agent.runtime.RunStatus
import app.pocketpilot.agent.runtime.stepLine
import app.pocketpilot.agent.runtime.transcript
import java.util.UUID

/** What the owner can do on [AgentCard]. */
data class AgentActions(
    val start: (goal: String, profileId: String) -> Unit,
    val pause: () -> Unit,
    val resume: () -> Unit,
    val stop: () -> Unit,
    /** Saves a profile; a non-null key replaces the stored one. */
    val saveProfile: (AgentProfile, apiKey: String?) -> Unit,
    val deleteProfile: (String) -> Unit,
    val hasKey: (AgentProfile) -> Boolean,
    /** Lists the server's models with the given settings and reports the outcome in plain words. */
    val testProfile: (AgentProfile, apiKey: String?, onResult: (String) -> Unit) -> Unit,
)

/**
 * Agent mode (spec section 9): model profiles, a new task, the current run with takeover and stop,
 * and earlier runs' transcripts.
 */
@Composable
fun AgentCard(
    profiles: List<AgentProfile>,
    current: RunState?,
    history: List<RunState>,
    actions: AgentActions,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.feature_agent_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.feature_agent_explainer), style = MaterialTheme.typography.bodySmall)
            ProfilesSection(profiles, actions)
            if (profiles.isNotEmpty()) {
                HorizontalDivider()
                TaskSection(profiles, running = current?.active == true, actions)
            }
            current?.let {
                HorizontalDivider()
                Text(stringResource(R.string.feature_agent_run_title), style = MaterialTheme.typography.titleSmall)
                RunView(it, expandedByDefault = true)
                if (it.active) RunControls(it.status, actions)
            }
            val earlier = history.filter { it.id != current?.id }.take(HISTORY_SHOWN)
            if (earlier.isNotEmpty()) {
                HorizontalDivider()
                Text(stringResource(R.string.feature_agent_history), style = MaterialTheme.typography.titleSmall)
                earlier.forEach { RunView(it, expandedByDefault = false) }
            }
        }
    }
}

@Composable
private fun ProfilesSection(
    profiles: List<AgentProfile>,
    actions: AgentActions,
) {
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    Text(stringResource(R.string.feature_agent_profiles), style = MaterialTheme.typography.titleSmall)
    if (profiles.isEmpty() && editing == null) {
        Text(stringResource(R.string.feature_agent_no_profiles), style = MaterialTheme.typography.bodySmall)
    }
    for (profile in profiles) {
        if (editing == profile.id) {
            ProfileForm(profile, actions.hasKey(profile), actions, onDone = { editing = null })
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(profile.name)
                    Text("${profile.kind.displayName} · ${profile.model}", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { editing = profile.id }) { Text(stringResource(R.string.feature_agent_edit)) }
            }
        }
    }
    if (editing == NEW) {
        ProfileForm(null, hasKey = false, actions, onDone = { editing = null })
    } else if (editing == null) {
        OutlinedButton(onClick = { editing = NEW }) { Text(stringResource(R.string.feature_agent_add_profile)) }
    }
}

@Composable
private fun ProfileForm(
    existing: AgentProfile?,
    hasKey: Boolean,
    actions: AgentActions,
    onDone: () -> Unit,
) {
    var kind by rememberSaveable { mutableStateOf(existing?.kind ?: ProviderKind.GEMINI) }
    var name by rememberSaveable { mutableStateOf(existing?.name ?: "") }
    var model by rememberSaveable { mutableStateOf(existing?.model ?: defaultModel(kind)) }
    var baseUrl by rememberSaveable { mutableStateOf(existing?.baseUrl ?: "") }
    var key by rememberSaveable { mutableStateOf("") }
    var cost by rememberSaveable { mutableStateOf(existing?.maxCostUsd?.toString() ?: "") }
    var testResult by rememberSaveable { mutableStateOf<String?>(null) }

    fun profile() =
        AgentProfile(
            id = existing?.id ?: UUID.randomUUID().toString(),
            name = name.trim().ifBlank { kind.displayName },
            kind = kind,
            model = model.trim(),
            baseUrl = baseUrl.trim().ifBlank { null }.takeIf { kind == ProviderKind.OPENAI_COMPATIBLE },
            maxCostUsd = cost.trim().toDoubleOrNull(),
        )

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.feature_agent_provider), style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (k in ProviderKind.entries) {
                FilterChip(
                    selected = kind == k,
                    onClick = {
                        if (model.isBlank() || model == defaultModel(kind)) model = defaultModel(k)
                        kind = k
                    },
                    label = { Text(shortName(k)) },
                )
            }
        }
        OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.feature_agent_profile_name)) }, singleLine = true)
        OutlinedTextField(model, { model = it }, label = { Text(stringResource(R.string.feature_agent_model)) }, singleLine = true)
        if (kind == ProviderKind.OPENAI_COMPATIBLE) {
            OutlinedTextField(
                baseUrl,
                { baseUrl = it },
                label = { Text(stringResource(R.string.feature_agent_base_url)) },
                supportingText = { Text(stringResource(R.string.feature_agent_base_url_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
        }
        OutlinedTextField(
            key,
            { key = it },
            label = { Text(stringResource(R.string.feature_agent_api_key)) },
            supportingText = { if (hasKey) Text(stringResource(R.string.feature_agent_api_key_kept)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        OutlinedTextField(
            cost,
            { cost = it },
            label = { Text(stringResource(R.string.feature_agent_cost_limit)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
        testResult?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        val valid = model.isNotBlank() && (kind != ProviderKind.OPENAI_COMPATIBLE || baseUrl.startsWith("http"))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = valid,
                onClick = {
                    actions.saveProfile(profile(), key.trim().ifBlank { null })
                    onDone()
                },
            ) { Text(stringResource(R.string.feature_agent_save)) }
            TextButton(enabled = valid, onClick = {
                testResult = "…"
                actions.testProfile(profile(), key.trim().ifBlank { null }) { testResult = it }
            }) { Text(stringResource(R.string.feature_agent_test)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onDone) { Text(stringResource(R.string.feature_agent_cancel)) }
            if (existing != null) {
                TextButton(onClick = {
                    actions.deleteProfile(existing.id)
                    onDone()
                }) { Text(stringResource(R.string.feature_agent_delete)) }
            }
        }
    }
}

@Composable
private fun TaskSection(
    profiles: List<AgentProfile>,
    running: Boolean,
    actions: AgentActions,
) {
    var goal by rememberSaveable { mutableStateOf("") }
    var profileId by rememberSaveable { mutableStateOf(profiles.first().id) }
    val selected = profiles.firstOrNull { it.id == profileId } ?: profiles.first()
    OutlinedTextField(
        goal,
        { goal = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.feature_agent_task)) },
        placeholder = { Text(stringResource(R.string.feature_agent_task_hint)) },
        minLines = 2,
    )
    if (profiles.size > 1) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (p in profiles) {
                FilterChip(selected = p.id == selected.id, onClick = { profileId = p.id }, label = { Text(p.name) })
            }
        }
    }
    Button(enabled = goal.isNotBlank() && !running, onClick = { actions.start(goal.trim(), selected.id) }) {
        Text(stringResource(R.string.feature_agent_start))
    }
}

@Composable
private fun RunControls(
    status: RunStatus,
    actions: AgentActions,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (status == RunStatus.PAUSED) {
            Button(onClick = actions.resume) { Text(stringResource(R.string.feature_agent_resume)) }
        } else {
            OutlinedButton(onClick = actions.pause) { Text(stringResource(R.string.feature_agent_pause)) }
        }
        OutlinedButton(onClick = actions.stop) { Text(stringResource(R.string.feature_agent_stop)) }
    }
}

@Composable
private fun RunView(
    run: RunState,
    expandedByDefault: Boolean,
) {
    var expanded by rememberSaveable(run.id) { mutableStateOf(expandedByDefault) }
    val clipboard = LocalClipboardManager.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(run.goal, style = MaterialTheme.typography.bodyMedium)
        Text(
            stringResource(R.string.feature_agent_status, statusText(run.status), run.steps.count { it.note == null }, run.model),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            stringResource(R.string.feature_agent_tokens, run.inputTokens, run.outputTokens) +
                (run.costUsd?.let { "  " + stringResource(R.string.feature_agent_cost, it) } ?: ""),
            style = MaterialTheme.typography.bodySmall,
        )
        run.outcome?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        if (expanded) {
            run.steps.takeLast(STEPS_SHOWN).forEach {
                Text(stepLine(it), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (run.steps.isNotEmpty()) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(stringResource(if (expanded) R.string.feature_agent_hide_steps else R.string.feature_agent_show_steps))
                }
            }
            TextButton(
                onClick = { clipboard.setText(AnnotatedString(transcript(run))) },
            ) { Text(stringResource(R.string.feature_agent_copy)) }
        }
    }
}

@Composable
private fun statusText(status: RunStatus): String =
    stringResource(
        when (status) {
            RunStatus.RUNNING -> R.string.feature_agent_status_running
            RunStatus.PAUSED -> R.string.feature_agent_status_paused
            RunStatus.FINISHED -> R.string.feature_agent_status_finished
            RunStatus.STOPPED -> R.string.feature_agent_status_stopped
            RunStatus.OUT_OF_BUDGET -> R.string.feature_agent_status_budget
            RunStatus.FAILED -> R.string.feature_agent_status_failed
        },
    )

private fun shortName(kind: ProviderKind): String =
    when (kind) {
        ProviderKind.ANTHROPIC -> "Claude"
        ProviderKind.OPENAI -> "OpenAI"
        ProviderKind.GEMINI -> "Gemini"
        ProviderKind.OPENAI_COMPATIBLE -> "Ollama/other"
    }

private fun defaultModel(kind: ProviderKind): String =
    when (kind) {
        ProviderKind.ANTHROPIC -> "claude-sonnet-5-5"
        ProviderKind.OPENAI -> "gpt-4.1-mini"
        ProviderKind.GEMINI -> "gemini-2.5-flash"
        ProviderKind.OPENAI_COMPATIBLE -> "qwen2.5:7b"
    }

private const val NEW = "new"
private const val HISTORY_SHOWN = 10
private const val STEPS_SHOWN = 30
