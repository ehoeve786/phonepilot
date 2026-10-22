package app.pocketpilot.feature.automations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
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
import app.pocketpilot.designsystem.LiquidGlassBentoCard

/** One variable being added or changed. [oldName] is null for a new variable. */
internal data class VariableEdit(
    val oldName: String?,
    val name: String,
    val value: String,
)

/** The global variables, by name. Tap one to change it, or add one. Each row has a remove button. */
@Composable
internal fun VariablesList(
    variables: Map<String, String>,
    onSet: (String, String?) -> Unit,
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
            VariableRow(
                name = name,
                value = value,
                onClick = { editing = VariableEdit(name, name, value) },
                onDelete = { onSet(name, null) },
            )
        }
        item {
            FilledTonalButton(onClick = { editing = VariableEdit(null, "", "") }) {
                Text(stringResource(R.string.feature_automations_add_variable))
            }
        }
    }

    editing?.let { edit ->
        VariableDialog(
            edit = edit,
            onDismiss = { editing = null },
            onSave = { oldName, name, value ->
                // A renamed variable replaces the old name, so the old one is removed.
                if (oldName != null && oldName != name) onSet(oldName, null)
                onSet(name, value)
                editing = null
            },
            onDelete = {
                onSet(it, null)
                editing = null
            },
        )
    }
}

/** A variable in a list: its name and value, tap to change it, and a button to remove it. */
@Composable
internal fun VariableRow(
    name: String,
    value: String,
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
 * Adds or changes one variable. [onSave] gets the old name (null for a new one), the new name and the value.
 * [onDelete] is offered only for an existing variable.
 */
@Composable
internal fun VariableDialog(
    edit: VariableEdit,
    onDismiss: () -> Unit,
    onSave: (oldName: String?, name: String, value: String) -> Unit,
    onDelete: (String) -> Unit,
) {
    var current by remember(edit) { mutableStateOf(edit) }
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
                OutlinedTextField(
                    value = current.value,
                    onValueChange = { current = current.copy(value = it) },
                    label = { Text(stringResource(R.string.feature_automations_variable_value)) },
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = current.name.isNotBlank(),
                onClick = { onSave(edit.oldName, current.name.trim(), current.value) },
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
