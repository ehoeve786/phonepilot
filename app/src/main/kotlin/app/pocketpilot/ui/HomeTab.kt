package app.pocketpilot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.pocketpilot.R
import app.pocketpilot.capability.shizuku.ShizukuState
import app.pocketpilot.core.audit.AuditEvent
import app.pocketpilot.core.model.RiskTier
import app.pocketpilot.designsystem.BentoGap
import app.pocketpilot.designsystem.BentoPair
import app.pocketpilot.designsystem.LiquidGlassBentoCard
import app.pocketpilot.designsystem.RiskTierTag
import app.pocketpilot.designsystem.agent.AgentCommandBar
import app.pocketpilot.designsystem.automation.PinnedRuleUi
import app.pocketpilot.designsystem.automation.PinnedRulesSection
import app.pocketpilot.designsystem.status.ConnectedClient
import app.pocketpilot.designsystem.status.McpLiveStatusHeader
import app.pocketpilot.designsystem.status.McpStatus
import app.pocketpilot.designsystem.status.ProviderHealthCard
import app.pocketpilot.designsystem.status.ProviderHealthUi
import app.pocketpilot.designsystem.status.ProviderLight
import app.pocketpilot.designsystem.status.RemoteLink
import app.pocketpilot.designsystem.status.ServerPhase
import app.pocketpilot.designsystem.status.TelemetryCard
import app.pocketpilot.designsystem.status.TelemetryItemUi
import app.pocketpilot.feature.clients.ClientRow
import app.pocketpilot.feature.onboarding.SetupStep
import app.pocketpilot.network.api.NetworkState
import app.pocketpilot.server.http.ServerState
import app.pocketpilot.core.designsystem.R as DsR

/** Something on Home's "Needs you" list waiting for the owner's answer. */
data class PendingApproval(
    val id: String,
    /** "Claude wants to run settings.set" or "Claude wants to connect to this phone". */
    val title: String,
    /** What exactly will change, shown verbatim. */
    val detail: String?,
    val tier: RiskTier?,
    /** True when Allow opens the full approval screen, as consent does so the owner can pick permissions. */
    val opensScreen: Boolean = false,
)

/** What Home shows, gathered so the phone and tablet layouts share it. */
internal class HomeTabState(
    val serverState: ServerState,
    val remoteState: NetworkState,
    val wireGuardState: NetworkState,
    val clients: List<ClientRow>,
    val auditEvents: List<AuditEvent>,
    val tiers: Map<String, RiskTier>,
    val crashReport: String?,
    val clientsStopped: Boolean,
    val approvals: List<PendingApproval>,
    val setupSteps: List<SetupStep>,
    val shizukuState: ShizukuState,
    val pinnedRules: List<PinnedRuleUi>,
    val token: String,
    /** Agent mode's models in fallback order, with how each last answered. */
    val providers: List<HomeProvider> = emptyList(),
    /** The phone's battery and network, refreshed while Home is in front; null until first read. */
    val telemetry: HomeTelemetry? = null,
)

/** How a model last answered, from Agent mode's runs. */
enum class HomeProviderState { UNKNOWN, READY, LIMITED, DOWN }

/** One model of the owner's chain. [retryAtMillis] is set while rate limited and the server said when to try again. */
class HomeProvider(
    val name: String,
    val state: HomeProviderState,
    val retryAtMillis: Long? = null,
)

/** The phone's battery and network for the telemetry tile. */
class HomeTelemetry(
    val batteryPercent: Int?,
    val charging: Boolean,
    /** wifi, cellular, ethernet, vpn, other or none, as device.info names them. */
    val transport: String,
    val connected: Boolean,
)

/** Home's callbacks. */
internal class HomeTabActions(
    val onStart: () -> Unit,
    val onStop: () -> Unit,
    val onDismissCrash: () -> Unit,
    val onAllowClients: () -> Unit,
    val onApprove: (String) -> Unit,
    val onDecline: (String) -> Unit,
    val onPinnedToggle: (String, Boolean) -> Unit,
    val onSeeAllRules: () -> Unit,
    val onOpenSettings: () -> Unit,
    /** The quick prompt on Home: the owner's line for the agent. */
    val onAgentPrompt: (String) -> Unit = {},
)

/**
 * Home: is the server up and who is connected, what is the AI doing right now, and does anything need
 * the owner. On wide screens the status column sits left of the live log.
 */
@Composable
internal fun HomeTab(
    state: HomeTabState,
    actions: HomeTabActions,
    wide: Boolean,
    modifier: Modifier = Modifier,
) {
    val now by rememberNow()
    var filter by rememberSaveable { mutableStateOf(LogFilter.ALL) }
    var opened by remember { mutableStateOf<AuditEvent?>(null) }
    val events =
        state.auditEvents
            .take(LIVE_LOG_SIZE)
            .filteredBy(filter, state.tiers)
    val status = mcpStatus(state, now)
    if (wide) {
        Row(modifier.fillMaxSize()) {
            Column(
                modifier =
                    Modifier
                        .width(STATUS_PANE_WIDTH)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                StatusColumn(state, actions, status, now)
            }
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 16.dp),
            ) {
                liveLog(events, state.tiers, now, filter, { filter = it }, { opened = it })
            }
        }
    } else {
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 16.dp),
        ) {
            item {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    StatusColumn(state, actions, status, now)
                }
            }
            liveLog(events, state.tiers, now, filter, { filter = it }, { opened = it })
        }
    }
    opened?.let { event -> AuditDetailSheet(event, state.tiers[event.tool]) { opened = null } }
}

@Composable
private fun StatusColumn(
    state: HomeTabState,
    actions: HomeTabActions,
    status: McpStatus,
    now: Long,
) {
    val clipboard = LocalClipboardManager.current
    McpLiveStatusHeader(
        status = status,
        onServerChange = { on -> if (on) actions.onStart() else actions.onStop() },
        onCopyEndpoint = { clipboard.setText(AnnotatedString(status.endpoint)) },
        onClientClick = { actions.onOpenSettings() },
    )
    AgentCommandBar(onSubmit = actions.onAgentPrompt)
    state.crashReport?.let { CrashCard(it, actions.onDismissCrash) }
    if (state.clientsStopped) StoppedCard(actions.onAllowClients)
    NeedsYou(state.approvals, doctorProblems(state.setupSteps, state.shizukuState), actions)
    // The Bento row: rules take the wider tile, the glanceable numbers stack in the narrower one.
    BentoPair(
        start = { tile ->
            PinnedRulesSection(
                rules = state.pinnedRules,
                onToggle = actions.onPinnedToggle,
                onSeeAll = actions.onSeeAllRules,
                modifier = tile,
            )
        },
        end = { tile ->
            Column(tile, verticalArrangement = Arrangement.spacedBy(BentoGap)) {
                ProviderHealthCard(providerRows(state.providers, now))
                TelemetryCard(telemetryItems(state, now))
            }
        },
    )
    ConnectCard(state.serverState, state.token)
}

/** Pending approvals and Doctor problems; hidden when nothing waits. */
@Composable
private fun NeedsYou(
    approvals: List<PendingApproval>,
    problems: List<SetupStep>,
    actions: HomeTabActions,
) {
    if (approvals.isEmpty() && problems.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.home_needs_you), style = MaterialTheme.typography.titleMedium)
        approvals.forEach { approval -> ApprovalRow(approval, actions) }
        problems.forEach { step ->
            val label = step.actionLabel
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                ListItem(
                    leadingContent = { Icon(painterResource(DsR.drawable.core_designsystem_ic_warning), contentDescription = null) },
                    headlineContent = { Text(step.title) },
                    supportingContent = { Text(step.reason, maxLines = 2) },
                    trailingContent =
                        if (label != null) {
                            { FilledTonalButton(onClick = step.action) { Text(label) } }
                        } else {
                            null
                        },
                    colors =
                        ListItemDefaults.colors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            headlineColor = MaterialTheme.colorScheme.onErrorContainer,
                            supportingColor = MaterialTheme.colorScheme.onErrorContainer,
                            leadingIconColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                )
            }
        }
    }
}

@Composable
private fun ApprovalRow(
    approval: PendingApproval,
    actions: HomeTabActions,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(DsR.drawable.core_designsystem_ic_shield), contentDescription = null)
                Text(
                    approval.title,
                    style = MaterialTheme.typography.titleSmall,
                    modifier =
                        Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp),
                )
                approval.tier?.let { RiskTierTag(it) }
            }
            approval.detail?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = { actions.onDecline(approval.id) }) { Text(stringResource(R.string.home_approval_deny)) }
                FilledTonalButton(onClick = { actions.onApprove(approval.id) }) {
                    Text(stringResource(if (approval.opensScreen) R.string.home_approval_review else R.string.home_approval_allow))
                }
            }
        }
    }
}

/** The Live section of the tool log: header, filter, then one row per call, newest first. */
private fun LazyListScope.liveLog(
    events: List<AuditEvent>,
    tiers: Map<String, RiskTier>,
    now: Long,
    filter: LogFilter,
    onFilterChange: (LogFilter) -> Unit,
    onOpen: (AuditEvent) -> Unit,
) {
    item {
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.home_live), style = MaterialTheme.typography.titleMedium)
            LogFilterRow(filter, onFilterChange)
        }
    }
    if (events.isEmpty()) {
        item {
            Text(
                stringResource(R.string.log_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
    items(events, key = { it.id }) { event ->
        AuditRow(event, tiers[event.tool], now, onClick = { onOpen(event) })
    }
}

/** The provider tile's rows: a rate limit shows how long until the model can be tried again. */
@Composable
private fun providerRows(
    providers: List<HomeProvider>,
    now: Long,
): List<ProviderHealthUi> =
    providers.map { provider ->
        val waitMs = provider.retryAtMillis?.let { it - now }?.takeIf { it > 0 }
        when (provider.state) {
            HomeProviderState.READY -> {
                ProviderHealthUi(provider.name, ProviderLight.Ready)
            }

            HomeProviderState.LIMITED -> {
                when {
                    waitMs != null -> {
                        val seconds = ((waitMs + MS_PER_SECOND - 1) / MS_PER_SECOND).toInt()
                        val detail =
                            if (seconds < SECONDS_AS_MINUTES) {
                                stringResource(R.string.home_provider_back_seconds, seconds)
                            } else {
                                stringResource(R.string.home_provider_back_minutes, (seconds + SECONDS_PER_MINUTE - 1) / SECONDS_PER_MINUTE)
                            }
                        ProviderHealthUi(provider.name, ProviderLight.Limited, detail)
                    }

                    // The wait is over, but nothing has tried the model since.
                    provider.retryAtMillis != null -> {
                        ProviderHealthUi(provider.name, ProviderLight.Unknown)
                    }

                    else -> {
                        ProviderHealthUi(provider.name, ProviderLight.Limited, stringResource(R.string.home_provider_quota))
                    }
                }
            }

            HomeProviderState.DOWN -> {
                ProviderHealthUi(provider.name, ProviderLight.Down)
            }

            HomeProviderState.UNKNOWN -> {
                ProviderHealthUi(provider.name, ProviderLight.Unknown)
            }
        }
    }

/** The telemetry tile's readings: battery, network and how busy the server has been. */
@Composable
private fun telemetryItems(
    state: HomeTabState,
    now: Long,
): List<TelemetryItemUi> {
    val telemetry = state.telemetry
    val battery =
        telemetry?.batteryPercent?.let { percent ->
            val value =
                if (telemetry.charging) {
                    stringResource(R.string.home_telemetry_battery_charging, percent)
                } else {
                    stringResource(R.string.home_telemetry_battery_value, percent)
                }
            TelemetryItemUi(stringResource(R.string.home_telemetry_battery), value, percent / PERCENT)
        } ?: TelemetryItemUi(stringResource(R.string.home_telemetry_battery), stringResource(R.string.home_telemetry_unknown))
    val network =
        TelemetryItemUi(
            stringResource(R.string.home_telemetry_network),
            when {
                telemetry == null -> {
                    stringResource(R.string.home_telemetry_unknown)
                }

                !telemetry.connected -> {
                    stringResource(R.string.home_telemetry_offline)
                }

                else -> {
                    when (telemetry.transport) {
                        "wifi" -> stringResource(R.string.home_telemetry_wifi)
                        "cellular" -> stringResource(R.string.home_telemetry_cellular)
                        "ethernet" -> stringResource(R.string.home_telemetry_ethernet)
                        "vpn" -> stringResource(R.string.home_telemetry_vpn)
                        else -> stringResource(R.string.home_telemetry_connected)
                    }
                }
            },
        )
    val calls = state.auditEvents.count { now - it.timestampMillis < MS_PER_HOUR }
    return listOf(battery, network, TelemetryItemUi(stringResource(R.string.home_telemetry_calls), calls.toString()))
}

/** The status header's state, from the server, the remote networks, recent clients and the audit log. */
private fun mcpStatus(
    state: HomeTabState,
    now: Long,
): McpStatus {
    val running = state.serverState as? ServerState.Running
    val recentClients =
        state.clients
            .filter { client -> client.lastUsedAtMillis?.let { now - it < ACTIVE_CLIENT_MS } == true }
            .map { ConnectedClient(it.id, it.name) }
    // Local apps such as Claude Code use the token, not OAuth; the audit log still names them.
    val recentCallers =
        state.auditEvents
            .takeWhile { now - it.timestampMillis < ACTIVE_CLIENT_MS }
            .map { it.principal }
            .distinct()
            .filter { principal -> recentClients.none { it.name == principal } }
            .map { ConnectedClient("principal:$it", it) }
    val clients = if (running == null) emptyList() else recentClients + recentCallers
    val remoteHost =
        listOf(state.wireGuardState, state.remoteState)
            .firstNotNullOfOrNull { (it as? NetworkState.Connected)?.hostname }
    return McpStatus(
        phase =
            when {
                running == null -> ServerPhase.Stopped
                clients.isEmpty() -> ServerPhase.Idle
                else -> ServerPhase.Running
            },
        endpoint =
            when (val server = state.serverState) {
                is ServerState.Running -> remoteHost?.let { "https://$it/mcp" } ?: server.url
                is ServerState.Failed -> server.reason
                ServerState.Stopped -> DEFAULT_LOCAL_URL
            },
        clients = clients,
        remote =
            listOf(
                RemoteLink(TAILSCALE, state.remoteState is NetworkState.Connected),
                RemoteLink(WIREGUARD, state.wireGuardState is NetworkState.Connected),
            ),
        callInFlight =
            running != null &&
                state.auditEvents.firstOrNull()?.let { now - it.timestampMillis < IN_FLIGHT_MS } == true,
    )
}

/** Setup steps the app needs and lacks, plus Shizuku once it was set up and lost its permission. */
private fun doctorProblems(
    steps: List<SetupStep>,
    shizuku: ShizukuState,
): List<SetupStep> =
    steps.filter { step ->
        !step.done && (!step.optional || (step.id == SHIZUKU_STEP && shizuku == ShizukuState.PERMISSION_NEEDED))
    }

@Composable
internal fun CrashCard(
    report: String,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
        shape = MaterialTheme.shapes.large,
    ) {
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

@Composable
internal fun StoppedCard(onAllowAgain: () -> Unit) {
    LiquidGlassBentoCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.control_stopped_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.control_stopped_text), style = MaterialTheme.typography.bodySmall)
            Button(onClick = onAllowAgain) { Text(stringResource(R.string.control_allow_again)) }
        }
    }
}

@Composable
internal fun ConnectCard(
    state: ServerState,
    token: String,
) {
    val context = LocalContext.current
    val url = (state as? ServerState.Running)?.url ?: DEFAULT_LOCAL_URL
    val command = claudeCodeCommand(url, token)
    LiquidGlassBentoCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.connect_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.connect_explainer), style = MaterialTheme.typography.bodySmall)
            Text(
                claudeCodeCommand(url, "<token>"),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = { copySecret(context, command) }) { Text(stringResource(R.string.connect_copy)) }
        }
    }
}

/** The command that registers this phone's server with Claude Code. */
internal fun claudeCodeCommand(
    url: String,
    token: String,
): String = "claude mcp add --transport http pocketpilot $url --header \"Authorization: Bearer $token\""

/** Home keeps this many calls; Activity shows the whole audit log. */
private const val LIVE_LOG_SIZE = 200
private const val MS_PER_SECOND = 1_000L
private const val MS_PER_HOUR = 3_600_000L
private const val SECONDS_PER_MINUTE = 60
private const val SECONDS_AS_MINUTES = 100
private const val PERCENT = 100f
private const val CRASH_PREVIEW_LINES = 4
private const val IN_FLIGHT_MS = 2_000L

/** A client that called within this window counts as connected. */
private const val ACTIVE_CLIENT_MS = 10 * 60 * 1000L
private const val DEFAULT_LOCAL_URL = "http://127.0.0.1:8765/mcp"
private const val SHIZUKU_STEP = "shizuku"
private const val TAILSCALE = "Tailscale"
private const val WIREGUARD = "WireGuard"
private val STATUS_PANE_WIDTH = 360.dp
