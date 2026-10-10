package app.pocketpilot.feature.approvals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/** One permission on the consent screen. */
data class ScopeOption(
    /** The OAuth scope, such as `screen:read`. */
    val scope: String,
    /** Ticked when the screen opens. */
    val preselected: Boolean,
)

/** A client asking to connect, as the owner sees it. */
data class ConsentUi(
    val clientName: String,
    val redirectHost: String,
    val options: List<ScopeOption>,
)

/**
 * The full-screen consent prompt (spec section 8): who is asking, where the browser returns, and
 * which permissions to grant. Nothing is granted until the owner taps Allow.
 */
@Composable
fun ConsentScreen(
    ui: ConsentUi,
    onAllow: (Set<String>) -> Unit,
    onDeny: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var chosen by remember(ui) {
        mutableStateOf(
            ui.options
                .filter { it.preselected }
                .map { it.scope }
                .toSet(),
        )
    }
    Scaffold(modifier = modifier.fillMaxSize()) { padding ->
        Column(
            modifier =
                Modifier
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.feature_approvals_consent_title, ui.clientName), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.feature_approvals_consent_from, ui.redirectHost), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.feature_approvals_consent_explainer), style = MaterialTheme.typography.bodySmall)
            ui.options.forEach { option ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = option.scope in chosen,
                        onCheckedChange = { on -> chosen = if (on) chosen + option.scope else chosen - option.scope },
                    )
                    Column {
                        Text(scopeTitle(option.scope), style = MaterialTheme.typography.bodyLarge)
                        Text(option.scope, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Text(stringResource(R.string.feature_approvals_consent_warning), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onDeny) { Text(stringResource(R.string.feature_approvals_deny)) }
                Button(onClick = { onAllow(chosen) }, enabled = chosen.isNotEmpty()) {
                    Text(stringResource(R.string.feature_approvals_allow))
                }
            }
        }
    }
}

/** A tool call that needs the owner's yes (SENSITIVE and DESTRUCTIVE tools, or a confirm-all app). */
data class ConfirmUi(
    val principal: String,
    val toolTitle: String,
    val tool: String,
    val destructive: Boolean,
    val foregroundPackage: String?,
    /** Exactly what will run, such as a shell command. */
    val detail: String? = null,
)

@Composable
fun ConfirmScreen(
    ui: ConfirmUi,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(modifier = modifier.fillMaxSize()) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.feature_approvals_confirm_title, ui.principal), style = MaterialTheme.typography.headlineSmall)
            Text("${ui.toolTitle} (${ui.tool})", style = MaterialTheme.typography.bodyLarge)
            ui.foregroundPackage?.let {
                Text(stringResource(R.string.feature_approvals_confirm_in_app, it), style = MaterialTheme.typography.bodyMedium)
            }
            ui.detail?.let {
                Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
            }
            if (ui.destructive) {
                Text(
                    stringResource(R.string.feature_approvals_confirm_destructive),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onDeny) { Text(stringResource(R.string.feature_approvals_deny)) }
                Button(onClick = onAllow) { Text(stringResource(R.string.feature_approvals_allow)) }
            }
        }
    }
}

@Composable
private fun scopeTitle(scope: String): String =
    when (scope) {
        "screen:read" -> stringResource(R.string.feature_approvals_scope_screen_read)
        "ui:interact" -> stringResource(R.string.feature_approvals_scope_ui_interact)
        "apps:read" -> stringResource(R.string.feature_approvals_scope_apps_read)
        "apps:control" -> stringResource(R.string.feature_approvals_scope_apps_control)
        "device:read" -> stringResource(R.string.feature_approvals_scope_device_read)
        "clipboard:rw" -> stringResource(R.string.feature_approvals_scope_clipboard)
        "notify:read" -> stringResource(R.string.feature_approvals_scope_notify_read)
        "notify:act" -> stringResource(R.string.feature_approvals_scope_notify_act)
        "settings:write" -> stringResource(R.string.feature_approvals_scope_settings_write)
        "files:read" -> stringResource(R.string.feature_approvals_scope_files_read)
        "files:write" -> stringResource(R.string.feature_approvals_scope_files_write)
        "task:run" -> stringResource(R.string.feature_approvals_scope_task_run)
        else -> scope
    }
