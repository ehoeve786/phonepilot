package app.pocketpilot.feature.automations

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.pocketpilot.core.automation.ActionStep
import app.pocketpilot.core.automation.AutomationCatalog
import app.pocketpilot.core.automation.ConstraintGroup
import app.pocketpilot.core.automation.ConstraintNode
import app.pocketpilot.core.automation.ConstraintSpec
import app.pocketpilot.core.automation.Describe
import app.pocketpilot.core.automation.FieldDef
import app.pocketpilot.core.automation.GroupMode
import app.pocketpilot.core.automation.OnError
import app.pocketpilot.core.automation.Rule
import app.pocketpilot.core.automation.RuleValidator
import app.pocketpilot.core.automation.RunMode
import app.pocketpilot.core.automation.TriggerSpec
import app.pocketpilot.core.model.RiskTier

/** A picker or a settings sheet the editor has open; only one at a time. */
private sealed interface EditorSheet {
    data class Pick(
        val title: String,
        val items: List<PickerItem>,
        val onPick: (String) -> Unit,
    ) : EditorSheet

    data class Fields(
        val title: String,
        val description: String,
        val fields: List<FieldDef>,
        val params: Map<String, String>,
        val onDone: (Map<String, String>) -> Unit,
    ) : EditorSheet
}

/** Opens sheets for the editor's sections, so nested editors share one sheet slot. */
private class Sheets(
    /** False in the Play build, which hides sideload-only triggers and conditions. */
    private val sideload: Boolean,
) {
    var open by mutableStateOf<EditorSheet?>(null)

    fun pickTrigger(
        title: String,
        onPicked: (TriggerSpec) -> Unit,
    ) {
        open =
            EditorSheet.Pick(
                title,
                AutomationCatalog.triggers
                    .filter { sideload || !it.sideloadOnly }
                    .map { PickerItem(it.type, it.title, it.description, it.category) },
            ) { type ->
                val def = AutomationCatalog.trigger(type) ?: return@Pick
                if (def.fields.isEmpty()) {
                    open = null
                    onPicked(TriggerSpec(type))
                } else {
                    editFields(def.title, def.description, def.fields, emptyMap()) { onPicked(TriggerSpec(type, it)) }
                }
            }
    }

    fun pickConstraint(
        title: String,
        onPicked: (ConstraintSpec) -> Unit,
    ) {
        open =
            EditorSheet.Pick(
                title,
                AutomationCatalog.constraints
                    .filter { sideload || !it.sideloadOnly }
                    .map { PickerItem(it.type, it.title, it.description, it.category) },
            ) { type ->
                val def = AutomationCatalog.constraint(type) ?: return@Pick
                if (def.fields.isEmpty()) {
                    open = null
                    onPicked(ConstraintSpec(type))
                } else {
                    editFields(def.title, def.description, def.fields, emptyMap()) { onPicked(ConstraintSpec(type, it)) }
                }
            }
    }

    fun pickAction(
        title: String,
        sideload: Boolean,
        onPicked: (ActionStep) -> Unit,
    ) {
        open =
            EditorSheet.Pick(
                title,
                AutomationCatalog.actions
                    .filter { sideload || !it.sideloadOnly }
                    .map { PickerItem(it.type, it.title, it.description, it.category, it.tier) },
            ) { type ->
                val def = AutomationCatalog.action(type) ?: return@Pick
                if (def.fields.isEmpty()) {
                    open = null
                    onPicked(ActionStep(type))
                } else {
                    editFields(def.title, def.description, def.fields, emptyMap()) { onPicked(ActionStep(type, it)) }
                }
            }
    }

    fun editFields(
        title: String,
        description: String,
        fields: List<FieldDef>,
        params: Map<String, String>,
        onDone: (Map<String, String>) -> Unit,
    ) {
        open = EditorSheet.Fields(title, description, fields, params, onDone)
    }
}

/**
 * The full-screen rule builder: name, When (triggers), Only if (conditions in groups), Then (actions,
 * with flow steps holding more actions) or a task for Agent mode. Problems from [RuleValidator] are
 * listed above Save; Save stays available so a draft is never lost, but a rule with problems is
 * saved switched off.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RuleEditor(
    initial: Rule,
    state: AutomationsState,
    isNew: Boolean,
    onSave: (Rule) -> Unit,
    onClose: () -> Unit,
    onTest: (Rule) -> Unit,
    onDelete: (() -> Unit)?,
    onLoadApps: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var rule by remember(initial.id) { mutableStateOf(initial) }
    val sheets = remember(state.sideload) { Sheets(state.sideload) }
    val problems = RuleValidator.problems(rule, state.sideload)
    val context = FieldContext(state.apps, state.rules, state.tools, rule.id, onLoadApps)

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(if (isNew) R.string.feature_automations_new_rule else R.string.feature_automations_edit_rule)) },
            navigationIcon = {
                IconButton(onClick = onClose) {
                    Icon(
                        painterResource(R.drawable.feature_automations_ic_close),
                        contentDescription = stringResource(R.string.feature_automations_cancel),
                    )
                }
            },
            actions = {
                if (onDelete != null) {
                    IconButton(onClick = onDelete) {
                        Icon(
                            painterResource(R.drawable.feature_automations_ic_delete),
                            contentDescription = stringResource(R.string.feature_automations_delete),
                        )
                    }
                }
            },
        )
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = rule.name,
                onValueChange = { rule = rule.copy(name = it) },
                label = { Text(stringResource(R.string.feature_automations_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            EditorSection(stringResource(R.string.feature_automations_when), stringResource(R.string.feature_automations_when_text)) {
                rule.triggers.forEachIndexed { i, spec ->
                    val def = AutomationCatalog.trigger(spec.type)
                    ItemRow(
                        text = Describe.trigger(spec),
                        onClick = {
                            if (def != null && def.fields.isNotEmpty()) {
                                sheets.editFields(def.title, def.description, def.fields, spec.params) { params ->
                                    rule = rule.copy(triggers = rule.triggers.replace(i, spec.copy(params = params)))
                                }
                            }
                        },
                        onDelete = { rule = rule.copy(triggers = rule.triggers.without(i)) },
                    )
                }
                val add = stringResource(R.string.feature_automations_add_trigger)
                AddButton(add) { sheets.pickTrigger(add) { rule = rule.copy(triggers = rule.triggers + it) } }
                val variables = rule.triggers.flatMap { AutomationCatalog.trigger(it.type)?.variables.orEmpty() }.distinct()
                if (variables.isNotEmpty()) {
                    Text(
                        stringResource(R.string.feature_automations_variables, variables.joinToString(" ") { "{trigger.$it}" }),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            EditorSection(stringResource(R.string.feature_automations_only_if), stringResource(R.string.feature_automations_only_if_text)) {
                ConstraintGroupEditor(rule.constraints, sheets, depth = 0) { rule = rule.copy(constraints = it) }
            }

            EditorSection(stringResource(R.string.feature_automations_then), null) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    RunMode.entries.forEachIndexed { i, mode ->
                        SegmentedButton(
                            selected = rule.runAs == mode,
                            onClick = { rule = rule.copy(runAs = mode) },
                            shape = SegmentedButtonDefaults.itemShape(i, RunMode.entries.size),
                        ) {
                            Text(
                                stringResource(
                                    if (mode ==
                                        RunMode.DIRECT
                                    ) {
                                        R.string.feature_automations_run_direct
                                    } else {
                                        R.string.feature_automations_run_agent
                                    },
                                ),
                            )
                        }
                    }
                }
                if (rule.runAs == RunMode.AGENT) {
                    OutlinedTextField(
                        value = rule.agentPrompt,
                        onValueChange = { rule = rule.copy(agentPrompt = it) },
                        label = { Text(stringResource(R.string.feature_automations_agent_prompt)) },
                        supportingText = { Text(stringResource(R.string.feature_automations_agent_prompt_text)) },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    ActionListEditor(rule.actions, sheets, state.sideload, depth = 0) { rule = rule.copy(actions = it) }
                }
            }

            if (problems.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(R.string.feature_automations_problems), style = MaterialTheme.typography.titleSmall)
                        problems.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }

            SaveAndTestRow(
                onTest = { onTest(rule) },
                onSave = { onSave(if (problems.isEmpty()) rule else rule.copy(enabled = false)) },
            )
        }
    }

    when (val sheet = sheets.open) {
        is EditorSheet.Pick -> {
            CatalogPickerSheet(sheet.title, sheet.items, onPick = sheet.onPick, onDismiss = { sheets.open = null })
        }

        is EditorSheet.Fields -> {
            FieldsSheet(
                title = sheet.title,
                description = sheet.description,
                fields = sheet.fields,
                params = sheet.params,
                context = context,
                onDone = {
                    sheets.open = null
                    sheet.onDone(it)
                },
                onDismiss = { sheets.open = null },
            )
        }

        null -> {}
    }
}

@Composable
private fun EditorSection(
    title: String,
    hint: String?,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        if (hint != null) {
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        content()
    }
}

/** One trigger or condition: its sentence, tap to edit, a delete button. */
@Composable
private fun ItemRow(
    text: String,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(vertical = 12.dp))
            IconButton(onClick = onDelete) {
                Icon(
                    painterResource(R.drawable.feature_automations_ic_close),
                    contentDescription = stringResource(R.string.feature_automations_remove),
                )
            }
        }
    }
}

@Composable
private fun AddButton(
    label: String,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick) {
        Icon(painterResource(R.drawable.feature_automations_ic_add), contentDescription = null, Modifier.size(18.dp))
        Text(label, modifier = Modifier.padding(start = 4.dp))
    }
}

/** Conditions combined by All, Any, None or Exactly one, with groups nested up to [MAX_DEPTH]. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConstraintGroupEditor(
    group: ConstraintGroup,
    sheets: Sheets,
    depth: Int,
    onChange: (ConstraintGroup) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (group.items.size > 1 || depth > 0) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GroupMode.entries.forEach { mode ->
                    FilterChip(
                        selected = group.mode == mode,
                        onClick = { onChange(group.copy(mode = mode)) },
                        label = { Text(modeLabel(mode)) },
                    )
                }
            }
        }
        group.items.forEachIndexed { i, node ->
            when (node) {
                is ConstraintNode.Check -> {
                    val def = AutomationCatalog.constraint(node.spec.type)
                    ItemRow(
                        text = Describe.constraint(node.spec),
                        onClick = {
                            if (def != null && def.fields.isNotEmpty()) {
                                sheets.editFields(def.title, def.description, def.fields, node.spec.params) { params ->
                                    onChange(
                                        group.copy(items = group.items.replace(i, ConstraintNode.Check(node.spec.copy(params = params)))),
                                    )
                                }
                            }
                        },
                        onDelete = { onChange(group.copy(items = group.items.without(i))) },
                    )
                }

                is ConstraintNode.Group -> {
                    OutlinedCard(
                        modifier = Modifier.fillMaxWidth(),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Column(Modifier.padding(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    stringResource(R.string.feature_automations_group),
                                    style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(onClick = { onChange(group.copy(items = group.items.without(i))) }) {
                                    Icon(
                                        painterResource(R.drawable.feature_automations_ic_close),
                                        contentDescription = stringResource(R.string.feature_automations_remove),
                                    )
                                }
                            }
                            ConstraintGroupEditor(node.group, sheets, depth + 1) { inner ->
                                onChange(group.copy(items = group.items.replace(i, ConstraintNode.Group(inner))))
                            }
                        }
                    }
                }
            }
        }
        Row {
            val add = stringResource(R.string.feature_automations_add_condition)
            AddButton(add) { sheets.pickConstraint(add) { onChange(group.copy(items = group.items + ConstraintNode.Check(it))) } }
            if (depth < MAX_DEPTH) {
                AddButton(stringResource(R.string.feature_automations_add_group)) {
                    onChange(group.copy(items = group.items + ConstraintNode.Group(ConstraintGroup(mode = GroupMode.ANY))))
                }
            }
        }
    }
}

private const val MAX_DEPTH = 2

@Composable
private fun modeLabel(mode: GroupMode): String =
    stringResource(
        when (mode) {
            GroupMode.ALL -> R.string.feature_automations_mode_all
            GroupMode.ANY -> R.string.feature_automations_mode_any
            GroupMode.NONE -> R.string.feature_automations_mode_none
            GroupMode.ONE -> R.string.feature_automations_mode_one
        },
    )

/** Actions in order; flow actions hold their own lists, edited the same way one level in. */
@Composable
private fun ActionListEditor(
    steps: List<ActionStep>,
    sheets: Sheets,
    sideload: Boolean,
    depth: Int,
    onChange: (List<ActionStep>) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        steps.forEachIndexed { i, step ->
            ActionStepCard(
                index = i,
                step = step,
                sheets = sheets,
                sideload = sideload,
                depth = depth,
                canMoveUp = i > 0,
                canMoveDown = i < steps.lastIndex,
                onChange = { onChange(steps.replace(i, it)) },
                onMove = { delta -> onChange(steps.move(i, i + delta)) },
                onDelete = { onChange(steps.without(i)) },
            )
        }
        val add = stringResource(R.string.feature_automations_add_action)
        AddButton(add) { sheets.pickAction(add, sideload) { onChange(steps + it) } }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionStepCard(
    index: Int,
    step: ActionStep,
    sheets: Sheets,
    sideload: Boolean,
    depth: Int,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onChange: (ActionStep) -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
) {
    val def = AutomationCatalog.action(step.type)
    var more by rememberSaveable(step.type, index, depth) { mutableStateOf(!step.constraints.isEmpty || step.onError != OnError.STOP) }
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    Modifier
                        .weight(1f)
                        .clickable(enabled = def != null && def.fields.isNotEmpty()) {
                            if (def != null) {
                                sheets.editFields(def.title, def.description, def.fields, step.params) { onChange(step.copy(params = it)) }
                            }
                        }.padding(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "${index + 1}. ${Describe.action(step)}",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f, false),
                        )
                        val tier = step.tier()
                        if (tier >= RiskTier.SENSITIVE) TierTag(tier)
                    }
                    if (def == null) {
                        Text(step.type, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                    }
                }
                IconButton(onClick = { onMove(-1) }, enabled = canMoveUp) {
                    Icon(
                        painterResource(R.drawable.feature_automations_ic_up),
                        contentDescription = stringResource(R.string.feature_automations_move_up),
                    )
                }
                IconButton(onClick = { onMove(1) }, enabled = canMoveDown) {
                    Icon(
                        painterResource(R.drawable.feature_automations_ic_down),
                        contentDescription = stringResource(R.string.feature_automations_move_down),
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        painterResource(R.drawable.feature_automations_ic_delete),
                        contentDescription = stringResource(R.string.feature_automations_remove),
                    )
                }
            }

            val conditional = def?.conditional == true
            if (conditional) {
                Text(
                    stringResource(
                        if (step.type ==
                            "flow.if"
                        ) {
                            R.string.feature_automations_if_condition
                        } else {
                            R.string.feature_automations_step_condition_required
                        },
                    ),
                    style = MaterialTheme.typography.labelLarge,
                )
                ConstraintGroupEditor(step.constraints, sheets, depth = 1) { onChange(step.copy(constraints = it)) }
            }
            if (def?.container == true) {
                Column(Modifier.padding(start = 8.dp, top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(
                            if (step.type ==
                                "flow.if"
                            ) {
                                R.string.feature_automations_then
                            } else {
                                R.string.feature_automations_inner_actions
                            },
                        ),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    ActionListEditor(step.children, sheets, sideload, depth + 1) { onChange(step.copy(children = it)) }
                    if (step.type == "flow.if") {
                        Text(stringResource(R.string.feature_automations_else), style = MaterialTheme.typography.labelLarge)
                        ActionListEditor(step.elseChildren, sheets, sideload, depth + 1) { onChange(step.copy(elseChildren = it)) }
                    }
                }
            }

            if (!conditional) {
                TextButton(onClick = { more = !more }) {
                    Text(stringResource(if (more) R.string.feature_automations_less else R.string.feature_automations_more_options))
                }
                if (more) {
                    Column(Modifier.padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.feature_automations_step_condition), style = MaterialTheme.typography.labelLarge)
                        ConstraintGroupEditor(step.constraints, sheets, depth = 1) { onChange(step.copy(constraints = it)) }
                    }
                }
            }
            if (more || conditional) {
                Column(Modifier.padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.feature_automations_on_error), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = step.onError == OnError.STOP,
                            onClick = { onChange(step.copy(onError = OnError.STOP)) },
                            label = { Text(stringResource(R.string.feature_automations_on_error_stop)) },
                        )
                        FilterChip(
                            selected = step.onError == OnError.CONTINUE,
                            onClick = { onChange(step.copy(onError = OnError.CONTINUE)) },
                            label = { Text(stringResource(R.string.feature_automations_on_error_continue)) },
                        )
                    }
                }
            }
        }
    }
}

private fun <T> List<T>.replace(
    index: Int,
    value: T,
): List<T> = toMutableList().also { it[index] = value }

private fun <T> List<T>.without(index: Int): List<T> = toMutableList().also { it.removeAt(index) }

private fun <T> List<T>.move(
    from: Int,
    to: Int,
): List<T> {
    if (to !in indices) return this
    return toMutableList().also { it.add(to, it.removeAt(from)) }
}
