package app.pocketpilot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.pocketpilot.R
import app.pocketpilot.capability.api.BackendIds
import app.pocketpilot.capability.shizuku.ShizukuState
import app.pocketpilot.core.capabilities.CapabilityStatus
import app.pocketpilot.core.model.CapabilityId

/**
 * Doctor v1: which backend serves each capability right now, and what Shizuku needs. A capability
 * lists its backends in priority order, so a check mark on a later one means it is the fallback.
 */
@Composable
internal fun DoctorCard(
    capabilities: List<CapabilityStatus>,
    shizukuState: ShizukuState,
    onGrantShizuku: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.doctor_title), style = MaterialTheme.typography.titleMedium)
            capabilities.forEach { status ->
                Column {
                    Text(capabilityLabel(status.capability), style = MaterialTheme.typography.bodyMedium)
                    val backends =
                        status.backends.joinToString("  ") { backend ->
                            (if (backend.available) "✓ " else "✗ ") + backendLabel(backend.backendId)
                        }
                    Text(backends, style = MaterialTheme.typography.bodySmall)
                    if (status.active == null) {
                        Text(
                            stringResource(R.string.doctor_none),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            Text(stringResource(R.string.doctor_shizuku_title), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(
                    when (shizukuState) {
                        ShizukuState.NOT_RUNNING -> R.string.shizuku_not_running
                        ShizukuState.UNSUPPORTED -> R.string.shizuku_unsupported
                        ShizukuState.PERMISSION_NEEDED -> R.string.shizuku_permission_needed
                        ShizukuState.CONNECTING -> R.string.shizuku_connecting
                        ShizukuState.CONNECTED -> R.string.shizuku_connected
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            if (shizukuState == ShizukuState.PERMISSION_NEEDED) {
                Button(onClick = onGrantShizuku) { Text(stringResource(R.string.shizuku_grant)) }
            }
        }
    }
}

@Composable
private fun capabilityLabel(id: CapabilityId): String =
    stringResource(
        when (id) {
            CapabilityId.READ_UI -> R.string.capability_read_ui
            CapabilityId.INJECT_INPUT -> R.string.capability_inject_input
            CapabilityId.CAPTURE_SCREEN -> R.string.capability_capture_screen
            CapabilityId.LAUNCH_APPS -> R.string.capability_launch_apps
            else -> R.string.capability_other
        },
    )

@Composable
private fun backendLabel(id: String): String =
    when (id) {
        BackendIds.ACCESSIBILITY -> stringResource(R.string.backend_accessibility)
        BackendIds.SHIZUKU -> stringResource(R.string.backend_shizuku)
        BackendIds.PACKAGE_MANAGER -> stringResource(R.string.backend_package_manager)
        else -> id
    }
