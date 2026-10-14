package app.pocketpilot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.pocketpilot.R
import app.pocketpilot.capability.files.SharedFolder
import app.pocketpilot.capability.shizuku.ShizukuState
import app.pocketpilot.core.capabilities.CapabilityStatus
import app.pocketpilot.core.model.PolicyProfile
import app.pocketpilot.feature.clients.ClientRow
import app.pocketpilot.feature.clients.ClientsCard
import app.pocketpilot.feature.onboarding.SetupStep
import app.pocketpilot.network.api.NetworkState
import app.pocketpilot.network.api.PublicAccessState
import app.pocketpilot.core.designsystem.R as DsR

/** What the Settings screen shows. */
internal class SettingsState(
    val policyProfile: PolicyProfile,
    val versionName: String,
    val accessibilityOn: Boolean,
    val remoteState: NetworkState,
    val publicState: PublicAccessState,
    val clients: List<ClientRow>,
    val token: String,
    val sharedFolders: List<SharedFolder>,
    val biometricApprovals: Boolean,
    val capabilities: List<CapabilityStatus>,
    val shizukuState: ShizukuState,
    val setupSteps: List<SetupStep>,
)

/** The Settings screen's callbacks. */
internal class SettingsActions(
    val onOpenAccessibilitySettings: () -> Unit,
    val onOpenAppInfo: () -> Unit,
    val remoteActions: RemoteActions,
    val onRevokeClient: (String) -> Unit,
    val onKillSwitch: () -> Unit,
    val onRotateToken: () -> Unit,
    val onAddFolder: () -> Unit,
    val onRemoveFolder: (SharedFolder) -> Unit,
    val onBiometricChange: (Boolean) -> Unit,
    val onOpenSetup: () -> Unit,
    val onGrantShizuku: () -> Unit,
    val onAllowNotifications: () -> Unit,
)

/**
 * Settings, opened from the gear: Accessibility, remote access, connected apps, the local token,
 * shared folders, the biometric lock, the setup guide and Doctor.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
    state: SettingsState,
    actions: SettingsActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** WireGuard with the owner's relay, shown under Tailscale. */
    ownNetworkCard: @Composable () -> Unit = {},
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tab_settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painterResource(DsR.drawable.core_designsystem_ic_arrow_back),
                            contentDescription = stringResource(R.string.nav_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AccessibilityCard(state.accessibilityOn, actions.onOpenAccessibilitySettings, actions.onOpenAppInfo)
            RemoteAccessCard(
                state = state.remoteState,
                public = state.publicState,
                onTurnOn = actions.remoteActions.turnOn,
                onTurnOff = actions.remoteActions.turnOff,
                onSignIn = actions.remoteActions.signIn,
                onSignOut = actions.remoteActions.signOut,
                onPublicChange = actions.remoteActions.setPublic,
                logs = actions.remoteActions.logs,
            )
            ownNetworkCard()
            ClientsCard(state.clients, actions.onRevokeClient, actions.onKillSwitch)
            TokenCard(state.token, actions.onRotateToken)
            FoldersCard(state.sharedFolders, actions.onAddFolder, actions.onRemoveFolder)
            BiometricCard(state.biometricApprovals, actions.onBiometricChange)
            OutlinedButton(onClick = actions.onOpenSetup, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.home_setup_guide))
            }
            DoctorCard(
                state.capabilities,
                state.shizukuState,
                actions.onGrantShizuku,
                actions.onAllowNotifications,
                state.setupSteps,
            )
            Text(stringResource(R.string.home_tagline), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(
                    R.string.home_build,
                    state.policyProfile.flavor.name
                        .lowercase(),
                    state.versionName,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AccessibilityCard(
    on: Boolean,
    onOpenSettings: () -> Unit,
    onOpenAppInfo: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.accessibility_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(if (on) R.string.accessibility_on else R.string.accessibility_off))
            if (!on) {
                Text(stringResource(R.string.accessibility_explainer), style = MaterialTheme.typography.bodySmall)
                Button(onClick = onOpenSettings) { Text(stringResource(R.string.accessibility_open_settings)) }
                Text(stringResource(R.string.accessibility_restricted), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onOpenAppInfo) { Text(stringResource(R.string.accessibility_open_app_info)) }
            }
        }
    }
}

@Composable
private fun FoldersCard(
    folders: List<SharedFolder>,
    onAdd: () -> Unit,
    onRemove: (SharedFolder) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.folders_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.folders_explainer), style = MaterialTheme.typography.bodySmall)
            if (folders.isEmpty()) Text(stringResource(R.string.folders_none), style = MaterialTheme.typography.bodySmall)
            folders.forEach { folder ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(folder.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = { onRemove(folder) }) { Text(stringResource(R.string.folders_remove)) }
                }
            }
            Button(onClick = onAdd) { Text(stringResource(R.string.folders_add)) }
        }
    }
}

@Composable
private fun BiometricCard(
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.biometric_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = enabled, onCheckedChange = onChange)
            }
            Text(stringResource(R.string.biometric_explainer), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun TokenCard(
    token: String,
    onRotateToken: () -> Unit,
) {
    var visible by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.token_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.token_explainer), style = MaterialTheme.typography.bodySmall)
            Text(
                if (visible) token else "•".repeat(MASKED_LENGTH),
                fontFamily = FontFamily.Monospace,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { visible = !visible }) {
                    Text(stringResource(if (visible) R.string.token_hide else R.string.token_show))
                }
                TextButton(onClick = { copySecret(context, token) }) { Text(stringResource(R.string.token_copy)) }
                TextButton(onClick = onRotateToken) { Text(stringResource(R.string.token_rotate)) }
            }
        }
    }
}

private const val MASKED_LENGTH = 24
