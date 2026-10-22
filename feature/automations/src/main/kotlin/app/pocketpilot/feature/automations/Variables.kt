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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.pocketpilot.designsystem.LiquidGlassBentoCard

/** One variable being added or changed. [oldName] is null for a new variable. */
private data class VariableEdit(
    val oldName: String?,
    val name: String,
    val value: String,
)

/** The global variables, by name. Tap one to change or delete it, or add one. */
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
            LiquidGlassBentoCard(
                modifier = Modifier.fillMaxWidth(),
                onClick = { editing = VariableEdit(name, name, value) },
                contentPadding = PaddingValues(0.dp),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(name, style = MaterialTheme.typography.titleMedium)
                    Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            FilledTonalButton(onClick = { editing = VariableEdit(null, "", "") }) {
                Text(stringResource(R.string.feature_automations_add_variable))
            }
        }
    }

    editing?.let { edit ->
        AlertDialog(
            onDismissRequest = { editing = null },
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
                        value = edit.name,
                        onValueChange = { editing = edit.copy(name = it) },
                        label = { Text(stringResource(R.string.feature_automations_variable_name)) },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = edit.value,
                        onValueChange = { editing = edit.copy(value = it) },
                        label = { Text(stringResource(R.string.feature_automations_variable_value)) },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = edit.name.isNotBlank(),
                    onClick = {
                        val name = edit.name.trim()
                        // A renamed variable replaces the old name, so the old one is removed.
                        if (edit.oldName != null && edit.oldName != name) onSet(edit.oldName, null)
                        onSet(name, edit.value)
                        editing = null
                    },
                ) { Text(stringResource(R.string.feature_automations_save)) }
            },
            dismissButton = {
                Row {
                    if (edit.oldName != null) {
                        TextButton(
                            onClick = {
                                onSet(edit.oldName, null)
                                editing = null
                            },
                        ) { Text(stringResource(R.string.feature_automations_delete_variable)) }
                    }
                    TextButton(onClick = { editing = null }) { Text(stringResource(R.string.feature_automations_cancel)) }
                }
            },
        )
    }
}
