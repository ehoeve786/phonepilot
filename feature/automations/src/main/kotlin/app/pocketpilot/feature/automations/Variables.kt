package app.pocketpilot.feature.automations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.pocketpilot.core.automation.VariableType
import app.pocketpilot.designsystem.LiquidGlassBentoCard

/** One variable being added or changed. [oldName] is null for a new variable. */
internal data class VariableEdit(
    val oldName: String?,
    val name: String,
    val value: String,
    val type: VariableType,
)

/**
 * The global variables, by name, with their types. Tap one to change it, or add one. Each row has a remove button.
 * [onSet] gets the name, the value (null removes the variable) and the type.
 */
@Composable
internal fun VariablesList(
    variables: Map<String, String>,
    types: Map<String, VariableType>,
    onSet: (String, String?, VariableType?) -> Unit,
) {
    var editing by remember { mutableStateOf<VariableEdit?>(null) }
    val rows = variables.toList().sortedBy { it.first }
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                stringResource(R.string.feature_automations_variables_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (rows.isEmpty()) {
            item {
                Text(stringResource(R.string.feature_automations_no_global_variables), style = MaterialTheme.typography.bodyMedium)
            }
        }
        items(rows, key = { it.first }) { (name, value) ->
            val type = types[name] ?: VariableType.STRING
            VariableRow(
                name = name,
                value = value,
                typeLabel = type.label,
                onClick = { editing = VariableEdit(name, name, value, type) },
                onDelete = { onSet(name, null, null) },
            )
        }
        item {
            FilledTonalButton(onClick = { editing = VariableEdit(null, "", "", VariableType.STRING) }) {
                Text(stringResource(R.string.feature_automations_add_variable))
            }
        }
    }

    editing?.let { edit ->
        VariableDialog(
            edit = edit,
            onDismiss = { editing = null },
            onSave = { oldName, name, value, type ->
                // A renamed variable replaces the old name, so the old one is removed.
                if (oldName != null && oldName != name) onSet(oldName, null, null)
                onSet(name, value, type)
                editing = null
            },
            onDelete = {
                onSet(it, null, null)
                editing = null
            },
        )
    }
}

/** A variable in a list: its name, its type and value. Tap to change it, and a button to remove it. */
@Composable
internal fun VariableRow(
    name: String,
    value: String,
    typeLabel: String?,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    LiquidGlassBentoCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        contentPadding = PaddingValues(0.dp),
    ) {
        Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(name, style = MaterialTheme.typography.titleMedium)
                if (typeLabel != null) {
                    Text(typeLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
                Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onDelete) {
                Icon(
                    painterResource(R.drawable.feature_automations_ic_close),
                    contentDescription = stringResource(R.string.feature_automations_remove),
                )
            }
        }
    }
}

/**
 * Adds or changes one variable and its type. [onSave] gets the old name (null for a new one), the new name, the value
 * and the type; the value is saved only when the type accepts it. [onDelete] is offered only for an existing variable.
 * [showTypes] hides the type choice where the type is not kept, such as a rule's starting values.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VariableDialog(
    edit: VariableEdit,
    onDismiss: () -> Unit,
    onSave: (oldName: String?, name: String, value: String, type: VariableType) -> Unit,
    onDelete: (String) -> Unit,
    showTypes: Boolean = true,
) {
    var current by remember(edit) { mutableStateOf(edit) }
    val problem = runCatching { current.type.normalize(current.value) }.exceptionOrNull()?.message
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (edit.oldName == null) R.string.feature_automations_add_variable else R.string.feature_automations_edit_variable,
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = current.name,
                    onValueChange = { current = current.copy(name = it) },
                    label = { Text(stringResource(R.string.feature_automations_variable_name)) },
                    singleLine = true,
                )
                if (showTypes) {
                    Text(stringResource(R.string.feature_automations_variable_type), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        VariableType.entries.forEach { type ->
                            FilterChip(
                                selected = current.type == type,
                                onClick = { current = current.copy(type = type) },
                                label = { Text(type.label) },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = current.value,
                    onValueChange = { current = current.copy(value = it) },
                    label = { Text(stringResource(R.string.feature_automations_variable_value)) },
                    isError = problem != null,
                    supportingText = { problem?.let { Text(it) } },
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = current.name.isNotBlank() && problem == null,
                onClick = {
                    onSave(edit.oldName, current.name.trim(), current.type.normalize(current.value), current.type)
                },
            ) { Text(stringResource(R.string.feature_automations_save)) }
        },
        dismissButton = {
            Row {
                edit.oldName?.let { old ->
                    TextButton(onClick = { onDelete(old) }) {
                        Text(stringResource(R.string.feature_automations_delete_variable))
                    }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.feature_automations_cancel)) }
            }
        },
    )
}
