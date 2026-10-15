package app.pocketpilot.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.pocketpilot.R
import app.pocketpilot.capability.files.SharedFolder
import app.pocketpilot.capability.shizuku.ShizukuState
import app.pocketpilot.core.audit.AuditEvent
import app.pocketpilot.core.capabilities.CapabilityStatus
import app.pocketpilot.core.model.PolicyProfile
import app.pocketpilot.core.model.ToolSpec
import app.pocketpilot.core.policy.ToolMode
import app.pocketpilot.designsystem.PocketPilotTheme
import app.pocketpilot.designsystem.automation.PinnedRuleUi
import app.pocketpilot.feature.clients.ClientRow
import app.pocketpilot.feature.onboarding.SetupStep
import app.pocketpilot.network.api.NetworkState
import app.pocketpilot.network.api.PublicAccessState
import app.pocketpilot.server.http.ServerState
import app.pocketpilot.core.designsystem.R as DsR

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
 * The app's main screen: Home (server status, what needs the owner, pinned automations and the live
 * tool log), Chat (Agent mode as a conversation), Automations, Tools (per-tool Allow, Ask or Block, protected apps and the shell switch) and
 * Activity (the audit log and Agent mode). Settings opens from the gear in the top bar, or at the
 * rail's foot on wide screens.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    /** WireGuard with the owner's relay, shown under Tailscale. */
    ownNetworkCard: @Composable () -> Unit = {},
    /** Agent mode: model profiles and runs. */
    agentCard: @Composable () -> Unit = {},
    /** The Chat destination; its argument opens the model list under Activity. */
    chatContent: @Composable (openModels: () -> Unit) -> Unit = {},
    /** Counts up each time something outside, such as a reply notification, asks for the Chat tab. */
    openChat: Int = 0,
    /** The raw shell switch; the Play build has no shell tool, so it never shows there. */
    shellEnabled: Boolean = false,
    onShellChange: (Boolean) -> Unit = {},
    /** True after the owner pressed Stop on the "controlling this phone" pill. */
    clientsStopped: Boolean = false,
    onAllowClients: () -> Unit = {},
    onAllowNotifications: () -> Unit = {},
    /** Opens on Settings, for setup's remote access step. */
    startOnSettings: Boolean = false,
    /** The setup steps, which Doctor re-checks with a fix for each. */
    setupSteps: List<SetupStep> = emptyList(),
    onOpenSetup: () -> Unit = {},
    sharedFolders: List<SharedFolder> = emptyList(),
    onAddFolder: () -> Unit = {},
    onRemoveFolder: (SharedFolder) -> Unit = {},
    biometricApprovals: Boolean = true,
    onBiometricChange: (Boolean) -> Unit = {},
    /** WireGuard's state, for the status header's remote access chips. */
    wireGuardState: NetworkState = NetworkState.Stopped,
    /** Recent tool calls, newest first, from the audit sink. */
    auditEvents: List<AuditEvent> = emptyList(),
    /** Approvals waiting for the owner, shown under "Needs you". */
    approvals: List<PendingApproval> = emptyList(),
    onApprove: (String) -> Unit = {},
    onDecline: (String) -> Unit = {},
    /** Every tool the server exposes, for the Tools tab. */
    tools: List<ToolSpec> = emptyList(),
    /** The owner's per-tool modes; tools missing here keep their tier's default. */
    toolModes: Map<String, ToolMode> = emptyMap(),
    /** Sets a tool's mode; null returns it to its tier's default. */
    onToolModeChange: (String, ToolMode?) -> Unit = { _, _ -> },
    /** Package names PocketPilot never reads or acts in. */
    protectedApps: List<String> = emptyList(),
    /** The Automations destination, filled by the automations feature. */
    automationsContent: @Composable () -> Unit = {},
    /** AI proposals waiting; shown as a badge on Automations when above zero. */
    automationsBadge: Int = 0,
    /** Rules pinned to Home, at most four shown. */
    pinnedRules: List<PinnedRuleUi> = emptyList(),
    onPinnedToggle: (String, Boolean) -> Unit = { _, _ -> },
    /** "See all" under the pinned rules; Home switches to Automations as well. */
    onSeeAllRules: () -> Unit = {},
    /** Counts up each time something outside, such as a proposal notification, asks for the Automations tab. */
    openAutomations: Int = 0,
    /** Agent mode's models in fallback order, with how each last answered. */
    providers: List<HomeProvider> = emptyList(),
    /** Battery and network for Home's telemetry tile. */
    telemetry: HomeTelemetry? = null,
    /** The quick prompt on Home; the chat opens as well. */
    onAgentPrompt: (String) -> Unit = {},
) {
    var destination by rememberSaveable { mutableStateOf(Destination.HOME) }
    LaunchedEffect(openAutomations) { if (openAutomations > 0) destination = Destination.AUTOMATIONS }
    LaunchedEffect(openChat) { if (openChat > 0) destination = Destination.CHAT }
    var activityTab by rememberSaveable { mutableIntStateOf(0) }
    var settingsOpen by rememberSaveable { mutableStateOf(startOnSettings) }
    var confirmKill by rememberSaveable { mutableStateOf(false) }
    val tiers = remember(tools) { tools.associate { it.name to it.riskTier } }

    BackHandler(enabled = settingsOpen) { settingsOpen = false }
    BackHandler(enabled = !settingsOpen && destination != Destination.HOME) { destination = Destination.HOME }

    if (settingsOpen) {
        SettingsScreen(
            state =
                SettingsState(
                    policyProfile = policyProfile,
                    versionName = versionName,
                    accessibilityOn = accessibilityOn,
                    remoteState = remoteState,
                    publicState = publicState,
                    clients = clients,
                    token = token,
                    sharedFolders = sharedFolders,
                    biometricApprovals = biometricApprovals,
                    capabilities = capabilities,
                    shizukuState = shizukuState,
                    setupSteps = setupSteps,
                ),
            actions =
                SettingsActions(
                    onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                    onOpenAppInfo = onOpenAppInfo,
                    remoteActions = remoteActions,
                    onRevokeClient = onRevokeClient,
                    onKillSwitch = onKillSwitch,
                    onRotateToken = onRotateToken,
                    onAddFolder = onAddFolder,
                    onRemoveFolder = onRemoveFolder,
                    onBiometricChange = onBiometricChange,
                    onOpenSetup = onOpenSetup,
                    onGrantShizuku = onGrantShizuku,
                    onAllowNotifications = onAllowNotifications,
                ),
            onBack = { settingsOpen = false },
            modifier = modifier,
            ownNetworkCard = ownNetworkCard,
        )
        return
    }

    val wide = LocalConfiguration.current.screenWidthDp >= RAIL_MIN_WIDTH_DP
    val badges =
        mapOf(
            Destination.HOME to if (approvals.isNotEmpty()) DOT_BADGE else 0,
            Destination.AUTOMATIONS to automationsBadge,
        )
    Row(modifier.fillMaxSize()) {
        if (wide) {
            NavigationRail {
                Destination.entries.forEach { item ->
                    NavigationRailItem(
                        selected = destination == item,
                        onClick = { destination = item },
                        icon = { DestinationIcon(item, badges[item] ?: 0) },
                        label = { Text(stringResource(item.label)) },
                    )
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { settingsOpen = true }, modifier = Modifier.padding(bottom = 16.dp)) {
                    Icon(
                        painterResource(DsR.drawable.core_designsystem_ic_settings),
                        contentDescription = stringResource(R.string.tab_settings),
                    )
                }
            }
        }
        Scaffold(
            modifier = Modifier.weight(1f),
            // The chat pads itself for the keyboard. Scaffold must not add the keyboard height too, or the input floats above it.
            contentWindowInsets =
                if (destination == Destination.CHAT) {
                    ScaffoldDefaults.contentWindowInsets.exclude(WindowInsets.ime)
                } else {
                    ScaffoldDefaults.contentWindowInsets
                },
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text(stringResource(R.string.app_name)) },
                    navigationIcon = {
                        Image(
                            painterResource(R.drawable.ic_app_logo),
                            contentDescription = null,
                            modifier = Modifier.padding(start = 16.dp).size(32.dp),
                        )
                    },
                    actions = {
                        IconButton(onClick = { confirmKill = true }) {
                            Icon(
                                painterResource(DsR.drawable.core_designsystem_ic_power),
                                contentDescription = stringResource(R.string.nav_kill_switch),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                        if (!wide) {
                            IconButton(onClick = { settingsOpen = true }) {
                                Icon(
                                    painterResource(DsR.drawable.core_designsystem_ic_settings),
                                    contentDescription = stringResource(R.string.tab_settings),
                                )
                            }
                        }
                    },
                )
            },
            bottomBar = {
                if (!wide) {
                    NavigationBar {
                        Destination.entries.forEach { item ->
                            NavigationBarItem(
                                selected = destination == item,
                                onClick = { destination = item },
                                icon = { DestinationIcon(item, badges[item] ?: 0) },
                                label = { Text(stringResource(item.label)) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            val content = Modifier.padding(padding)
            when (destination) {
                Destination.HOME -> {
                    HomeTab(
                        state =
                            HomeTabState(
                                serverState = serverState,
                                remoteState = remoteState,
                                wireGuardState = wireGuardState,
                                clients = clients,
                                auditEvents = auditEvents,
                                tiers = tiers,
                                crashReport = crashReport,
                                clientsStopped = clientsStopped,
                                approvals = approvals,
                                setupSteps = setupSteps,
                                shizukuState = shizukuState,
                                pinnedRules = pinnedRules,
                                token = token,
                                providers = providers,
                                telemetry = telemetry,
                            ),
                        actions =
                            HomeTabActions(
                                onStart = onStart,
                                onStop = onStop,
                                onDismissCrash = onDismissCrash,
                                onAllowClients = onAllowClients,
                                onApprove = onApprove,
                                onDecline = onDecline,
                                onPinnedToggle = onPinnedToggle,
                                onSeeAllRules = {
                                    destination = Destination.AUTOMATIONS
                                    onSeeAllRules()
                                },
                                onOpenSettings = { settingsOpen = true },
                                onAgentPrompt = { text ->
                                    destination = Destination.CHAT
                                    onAgentPrompt(text)
                                },
                            ),
                        wide = wide,
                        modifier = content,
                    )
                }

                Destination.CHAT -> {
                    // The chat pads itself for the keyboard, so the bars' padding counts towards it.
                    Box(content.consumeWindowInsets(padding).fillMaxSize()) {
                        chatContent {
                            activityTab = ACTIVITY_AGENT_TAB
                            destination = Destination.ACTIVITY
                        }
                    }
                }

                Destination.AUTOMATIONS -> {
                    Box(content.fillMaxSize()) { automationsContent() }
                }

                Destination.TOOLS -> {
                    ToolsTab(
                        state =
                            ToolsTabState(
                                tools = tools,
                                modes = toolModes,
                                auditEvents = auditEvents,
                                protectedApps = protectedApps,
                                shellAvailable = policyProfile != PolicyProfile.PLAY,
                                shellEnabled = shellEnabled,
                            ),
                        onModeChange = onToolModeChange,
                        onShellChange = onShellChange,
                        modifier = content,
                    )
                }

                Destination.ACTIVITY -> {
                    ActivityTab(auditEvents, tiers, agentCard, activityTab, { activityTab = it }, modifier = content)
                }
            }
        }
    }

    if (confirmKill) {
        AlertDialog(
            onDismissRequest = { confirmKill = false },
            title = { Text(stringResource(R.string.nav_kill_switch_title)) },
            text = { Text(stringResource(R.string.nav_kill_switch_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmKill = false
                    onKillSwitch()
                }) {
                    Text(stringResource(R.string.nav_kill_switch_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmKill = false }) { Text(stringResource(R.string.nav_cancel)) }
            },
        )
    }
}

/** A destination's icon, with a dot ([DOT_BADGE]) or a count when [badge] is above zero. */
@Composable
private fun DestinationIcon(
    item: Destination,
    badge: Int,
) {
    BadgedBox(
        badge = {
            if (badge == DOT_BADGE) {
                Badge()
            } else if (badge > 0) {
                Badge { Text(badge.toString()) }
            }
        },
    ) {
        Icon(painterResource(item.icon), contentDescription = null)
    }
}

/** The five top-level destinations; Settings opens from the top bar instead. */
private enum class Destination(
    @param:StringRes val label: Int,
    @param:DrawableRes val icon: Int,
) {
    HOME(R.string.tab_home, DsR.drawable.core_designsystem_ic_dashboard),
    CHAT(R.string.nav_chat, DsR.drawable.core_designsystem_ic_chat),
    AUTOMATIONS(R.string.nav_automations, DsR.drawable.core_designsystem_ic_bolt),
    TOOLS(R.string.nav_tools, DsR.drawable.core_designsystem_ic_build),
    ACTIVITY(R.string.nav_activity, DsR.drawable.core_designsystem_ic_history),
}

/** Windows at least this wide (Medium and up) get a navigation rail instead of the bottom bar. */
private const val RAIL_MIN_WIDTH_DP = 600

/** Marks a badge that shows as a dot rather than a count. */
private const val DOT_BADGE = -1

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
            pinnedRules = listOf(PinnedRuleUi("r1", "Quiet at work", enabled = true)),
        )
    }
}
