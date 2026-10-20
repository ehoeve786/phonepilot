package app.pocketpilot.feature.automations

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.pocketpilot.core.automation.Category
import app.pocketpilot.core.automation.FieldDef
import app.pocketpilot.core.automation.FieldKind
import app.pocketpilot.core.automation.Rule
import app.pocketpilot.core.model.RiskTier

/** One entry of a catalog picker. */
internal data class PickerItem(
    val type: String,
    val title: String,
    val description: String,
    val category: Category,
    val tier: RiskTier? = null,
)

/** A searchable list of triggers, conditions or actions, filtered by category. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun CatalogPickerSheet(
    title: String,
    items: List<PickerItem>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<Category?>(null) }
    val categories = items.map { it.category }.distinct()
    val shown =
        items.filter { item ->
            (category == null || item.category == category) &&
                (query.isBlank() || item.title.contains(query, ignoreCase = true) || item.description.contains(query, ignoreCase = true))
        }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 16.dp).fillMaxHeight(SHEET_HEIGHT), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(R.string.feature_automations_search)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = category == null,
                    onClick = { category = null },
                    label = { Text(stringResource(R.string.feature_automations_all)) },
                )
                categories.forEach { c ->
                    FilterChip(selected = category == c, onClick = { category = if (category == c) null else c }, label = { Text(c.title) })
                }
            }
            LazyColumn(Modifier.weight(1f)) {
                items(shown, key = { it.type }) { item ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(item.type) }
                            .padding(vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(item.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f, fill = false))
                            item.tier?.takeIf { it >= RiskTier.SENSITIVE }?.let { TierTag(it) }
                        }
                        Text(
                            item.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

private const val SHEET_HEIGHT = 0.9f

/** What a field picker may need from the app. */
internal data class FieldContext(
    val apps: List<AppOption>,
    val rules: List<Rule>,
    val tools: List<String>,
    val currentRuleId: String,
    val loadApps: () -> Unit,
)

/** Edits [fields] of one trigger, condition or action, plus any [extra] settings, in a bottom sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FieldsSheet(
    title: String,
    description: String,
    fields: List<FieldDef>,
    params: Map<String, String>,
    context: FieldContext,
    onDone: (Map<String, String>) -> Unit,
    onDismiss: () -> Unit,
    extra: @Composable () -> Unit = {},
) {
    var values by remember { mutableStateOf(params) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            fields.forEach { field ->
                FieldEditor(field, values[field.key] ?: field.default.orEmpty(), context) { values = values + (field.key to it) }
            }
            extra()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.feature_automations_cancel)) }
                Button(
                    onClick = { onDone(values.filterValues { it.isNotEmpty() }) },
                ) { Text(stringResource(R.string.feature_automations_done)) }
            }
        }
    }
}

private val DAYS = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FieldEditor(
    field: FieldDef,
    value: String,
    context: FieldContext,
    onChange: (String) -> Unit,
) {
    val label = field.label + if (field.required) "" else " " + stringResource(R.string.feature_automations_optional)
    when (field.kind) {
        FieldKind.CHOICE -> {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(label, style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    field.choices.forEach { choice ->
                        FilterChip(selected = value == choice.value, onClick = { onChange(choice.value) }, label = { Text(choice.label) })
                    }
                }
            }
        }

        FieldKind.BOOLEAN -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(checked = value == "true", onCheckedChange = { onChange(it.toString()) })
            }
        }

        FieldKind.DAYS -> {
            val selected =
                value
                    .split(',')
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .toSet()
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(label, style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DAYS.forEach { day ->
                        FilterChip(
                            selected = day in selected,
                            onClick = {
                                val next = if (day in selected) selected - day else selected + day
                                onChange(DAYS.filter { it in next }.joinToString(","))
                            },
                            label = { Text(day.replaceFirstChar(Char::uppercase)) },
                        )
                    }
                }
            }
        }

        FieldKind.APP -> {
            AppField(label, value, context, onChange)
        }

        FieldKind.TIME -> {
            TimeField(label, value, onChange)
        }

        FieldKind.RULE -> {
            val others = context.rules.filter { it.id != context.currentRuleId && !it.pending }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(label, style = MaterialTheme.typography.labelLarge)
                if (others.isEmpty()) {
                    Text(
                        stringResource(R.string.feature_automations_no_other_rules),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    others.forEach { rule ->
                        FilterChip(selected = value == rule.id, onClick = { onChange(rule.id) }, label = { Text(rule.name) })
                    }
                }
            }
        }

        FieldKind.TOOL -> {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = onChange,
                    label = { Text(label) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    context.tools
                        .filter { value.isBlank() || (it.contains(value, ignoreCase = true) && it != value) }
                        .take(TOOL_SUGGESTIONS)
                        .forEach { tool -> FilterChip(selected = false, onClick = { onChange(tool) }, label = { Text(tool) }) }
                }
            }
        }

        else -> {
            OutlinedTextField(
                value = value,
                onValueChange = onChange,
                label = { Text(label) },
                placeholder = field.hint?.let { hint -> { Text(hint) } },
                singleLine = field.kind != FieldKind.MULTILINE && field.kind != FieldKind.JSON,
                minLines = if (field.kind == FieldKind.MULTILINE || field.kind == FieldKind.JSON) 3 else 1,
                textStyle =
                    if (field.kind == FieldKind.JSON) {
                        MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
                    } else {
                        MaterialTheme.typography.bodyLarge
                    },
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType =
                            when (field.kind) {
                                FieldKind.NUMBER -> KeyboardType.Decimal
                                else -> KeyboardType.Text
                            },
                    ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private const val TOOL_SUGGESTIONS = 8

/** A time of day as "HH:mm", chosen on a clock face instead of typed. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val (hour, minute) = parseTime(value) ?: (8 to 0)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(value.ifEmpty { stringResource(R.string.feature_automations_choose_time) })
        }
    }
    if (open) {
        // The state is created when the dialog opens, so each opening starts from the saved time.
        val state = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    onChange("%02d:%02d".format(state.hour, state.minute))
                    open = false
                }) { Text(stringResource(R.string.feature_automations_done)) }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.feature_automations_cancel)) } },
            text = { TimePicker(state = state) },
        )
    }
}

/** The hour and minute in "HH:mm", or null when [value] is not a time of day. */
internal fun parseTime(value: String): Pair<Int, Int>? {
    val parts = value.trim().split(':')
    if (parts.size != 2) return null
    val hour = parts[0].toIntOrNull()?.takeIf { it in 0..23 } ?: return null
    val minute = parts[1].toIntOrNull()?.takeIf { it in 0..59 } ?: return null
    return hour to minute
}

/** Package names as text, with a searchable list of installed apps to tick. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppField(
    label: String,
    value: String,
    context: FieldContext,
    onChange: (String) -> Unit,
) {
    var picking by remember { mutableStateOf(false) }
    val selected = value.split(',').map(String::trim).filter(String::isNotEmpty)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label) },
            supportingText = {
                if (selected.isNotEmpty()) {
                    Text(selected.joinToString { pkg -> context.apps.firstOrNull { it.packageName == pkg }?.label ?: pkg })
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = { picking = true }) { Text(stringResource(R.string.feature_automations_choose_apps)) }
    }
    if (picking) {
        LaunchedEffect(Unit) { context.loadApps() }
        var query by remember { mutableStateOf("") }
        var chosen by remember { mutableStateOf(selected.toSet()) }
        ModalBottomSheet(onDismissRequest = { picking = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.padding(horizontal = 16.dp).fillMaxHeight(SHEET_HEIGHT), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.feature_automations_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (context.apps.isEmpty()) {
                    Text(
                        stringResource(R.string.feature_automations_loading_apps),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                LazyColumn(Modifier.weight(1f)) {
                    items(
                        context.apps.filter {
                            query.isBlank() || it.label.contains(query, ignoreCase = true) ||
                                it.packageName.contains(query, ignoreCase = true)
                        },
                        key = { it.packageName },
                    ) { app ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    chosen =
                                        if (app.packageName in
                                            chosen
                                        ) {
                                            chosen - app.packageName
                                        } else {
                                            chosen + app.packageName
                                        }
                                }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = app.packageName in chosen, onCheckedChange = null)
                            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                Text(app.label, style = MaterialTheme.typography.bodyLarge)
                                Text(app.packageName, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 16.dp)) {
                    TextButton(onClick = { picking = false }) { Text(stringResource(R.string.feature_automations_cancel)) }
                    Button(
                        onClick = {
                            onChange(chosen.joinToString(","))
                            picking = false
                        },
                    ) { Text(stringResource(R.string.feature_automations_done)) }
                }
            }
        }
    }
}
