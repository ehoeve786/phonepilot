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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.pocketpilot.R
import app.pocketpilot.capability.shizuku.ShizukuState
import app.pocketpilot.core.capabilities.CapabilityStatus
import app.pocketpilot.core.model.PolicyProfile
import app.pocketpilot.feature.clients.ClientRow
import app.pocketpilot.feature.clients.ClientsCard
import app.pocketpilot.network.api.NetworkState
import app.pocketpilot.network.api.PublicAccessState
import app.pocketpilot.server.http.ServerState

/** What the remote access card can ask for. */
data class RemoteActions(
    val turnOn: () -> Unit,
    val turnOff: () -> Unit,
    val signIn: (String) -> Unit,
    val signOut: () -> Unit,
    val setPublic: (Boolean) -> Unit,
    /** The Tailscale node's recent log lines, for support. */
    val logs: () -> String = { "" },
)

/**
 * Home: start and stop the local MCP server, turn on the Accessibility service, see which backends
 * work in Doctor, set up remote access and connected apps, and connect Claude Code with the local token.
 */
@Composable
fun HomeScreen(
    policyProfile: PolicyProfile,
    versionName: String,
    serverState: ServerState,
    accessibilityOn: Boolean,
    capabilities: List<CapabilityStatus>,
    shizukuState: ShizukuState,
    crashReport: String?,
    onDismissCrash: () -> Unit,
    remoteState: NetworkState,
    publicState: PublicAccessState,
    clients: List<ClientRow>,
    token: String,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRotateToken: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenAppInfo: () -> Unit,
    onGrantShizuku: () -> Unit,
    remoteActions: RemoteActions,
    onRevokeClient: (String) -> Unit,
    onKillSwitch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(modifier = modifier.fillMaxSize()) { padding ->
        Column(
            modifier =
                Modifier
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge)
            Text(stringResource(R.string.home_tagline), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.home_build, policyProfile.flavor.name.lowercase(), versionName),
                style = MaterialTheme.typography.bodySmall,
            )
            crashReport?.let { CrashCard(it, onDismissCrash) }
            ServerCard(serverState, onStart, onStop)
            AccessibilityCard(accessibilityOn, onOpenAccessibilitySettings, onOpenAppInfo)
            DoctorCard(capabilities, shizukuState, onGrantShizuku)
            RemoteAccessCard(
                state = remoteState,
                public = publicState,
                onTurnOn = remoteActions.turnOn,
                onTurnOff = remoteActions.turnOff,
                onSignIn = remoteActions.signIn,
                onSignOut = remoteActions.signOut,
                onPublicChange = remoteActions.setPublic,
                logs = remoteActions.logs,
            )
            ClientsCard(clients, onRevokeClient, onKillSwitch)
            TokenCard(token, onRotateToken)
            ConnectCard(serverState, token)
        }
    }
}

@Composable
private fun CrashCard(
    report: String,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.crash_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.crash_text), style = MaterialTheme.typography.bodySmall)
            Text(
                report.lineSequence().take(CRASH_PREVIEW_LINES).joinToString("\n"),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { clipboard.setText(AnnotatedString(report)) }) { Text(stringResource(R.string.crash_copy)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.crash_dismiss)) }
            }
        }
    }
}

private const val CRASH_PREVIEW_LINES = 4

@Composable
private fun ServerCard(
    state: ServerState,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.server_title), style = MaterialTheme.typography.titleMedium)
            Text(
                when (state) {
                    ServerState.Stopped -> stringResource(R.string.server_stopped)
                    is ServerState.Running -> stringResource(R.string.server_running, state.url)
                    is ServerState.Failed -> stringResource(R.string.server_failed, state.reason)
                },
            )
            if (state is ServerState.Running) {
                OutlinedButton(onClick = onStop) { Text(stringResource(R.string.server_stop)) }
            } else {
                Button(onClick = onStart) { Text(stringResource(R.string.server_start)) }
            }
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
private fun TokenCard(
    token: String,
    onRotateToken: () -> Unit,
) {
    var visible by rememberSaveable { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
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
                TextButton(onClick = { clipboard.setText(AnnotatedString(token)) }) { Text(stringResource(R.string.token_copy)) }
                TextButton(onClick = onRotateToken) { Text(stringResource(R.string.token_rotate)) }
            }
        }
    }
}

@Composable
private fun ConnectCard(
    state: ServerState,
    token: String,
) {
    val clipboard = LocalClipboardManager.current
    val url = (state as? ServerState.Running)?.url ?: "http://127.0.0.1:8765/mcp"
    val command = claudeCodeCommand(url, token)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.connect_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.connect_explainer), style = MaterialTheme.typography.bodySmall)
            Text(
                claudeCodeCommand(url, "<token>"),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = { clipboard.setText(AnnotatedString(command)) }) { Text(stringResource(R.string.connect_copy)) }
        }
    }
}

/** The command that registers this phone's server with Claude Code. */
internal fun claudeCodeCommand(
    url: String,
    token: String,
): String = "claude mcp add --transport http pocketpilot $url --header \"Authorization: Bearer $token\""

private const val MASKED_LENGTH = 24

@Preview
@Composable
private fun HomeScreenPreview() {
    PocketPilotTheme {
        HomeScreen(
            policyProfile = PolicyProfile.OSS,
            versionName = "0.1.0",
            serverState = ServerState.Running(8765, 8766),
            accessibilityOn = false,
            capabilities = emptyList(),
            shizukuState = ShizukuState.NOT_RUNNING,
            crashReport = null,
            onDismissCrash = {},
            remoteState = NetworkState.Stopped,
            publicState = PublicAccessState(enabled = false),
            clients = emptyList(),
            token = "pp_example",
            onStart = {},
            onStop = {},
            onRotateToken = {},
            onOpenAccessibilitySettings = {},
            onOpenAppInfo = {},
            onGrantShizuku = {},
            remoteActions = RemoteActions({}, {}, {}, {}, {}),
            onRevokeClient = {},
            onKillSwitch = {},
        )
    }
}
