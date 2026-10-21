package app.pocketpilot

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.pocketpilot.agent.AgentCoordinator
import app.pocketpilot.agent.ScreenHandoff
import app.pocketpilot.agent.runtime.ProviderHealth
import app.pocketpilot.agent.runtime.ProviderState
import app.pocketpilot.approvals.ApprovalActivity
import app.pocketpilot.approvals.ApprovalCenter
import app.pocketpilot.approvals.ApprovalItem
import app.pocketpilot.automation.AutomationRuntime
import app.pocketpilot.automation.EventKey
import app.pocketpilot.automation.HomeAssistant
import app.pocketpilot.capability.accessibility.A11yBridge
import app.pocketpilot.capability.accessibility.PocketPilotA11yService
import app.pocketpilot.capability.api.DeviceInfoSource
import app.pocketpilot.capability.files.SafFileStore
import app.pocketpilot.capability.notifications.ListenerNotificationSource
import app.pocketpilot.capability.shizuku.ShizukuConnection
import app.pocketpilot.capability.shizuku.ShizukuShellRunner
import app.pocketpilot.capability.shizuku.ShizukuState
import app.pocketpilot.control.ControlOverlay
import app.pocketpilot.core.audit.AuditSink
import app.pocketpilot.core.automation.Rule
import app.pocketpilot.core.automation.RuleStore
import app.pocketpilot.core.automation.RuleValidator
import app.pocketpilot.core.automation.parseSharedRule
import app.pocketpilot.core.automation.usesShell
import app.pocketpilot.core.capabilities.CapabilityGraph
import app.pocketpilot.core.model.PolicyProfile
import app.pocketpilot.core.model.ToolSpec
import app.pocketpilot.core.orchestrator.ToolRegistry
import app.pocketpilot.core.policy.AppPolicy
import app.pocketpilot.core.policy.ToolMode
import app.pocketpilot.core.policy.ToolModes
import app.pocketpilot.designsystem.PocketPilotTheme
import app.pocketpilot.designsystem.automation.PinnedRuleUi
import app.pocketpilot.feature.agent.AgentActions
import app.pocketpilot.feature.agent.AgentCard
import app.pocketpilot.feature.agent.AgentChat
import app.pocketpilot.feature.agent.ChatActions
import app.pocketpilot.feature.automations.AutomationsActions
import app.pocketpilot.feature.automations.AutomationsScreen
import app.pocketpilot.feature.automations.AutomationsState
import app.pocketpilot.feature.clients.ClientRow
import app.pocketpilot.feature.network.OwnNetworkActions
import app.pocketpilot.feature.network.OwnNetworkCard
import app.pocketpilot.feature.onboarding.OnboardingScreen
import app.pocketpilot.network.api.NetworkState
import app.pocketpilot.network.api.SecretStore
import app.pocketpilot.network.certificates.CertificateManager
import app.pocketpilot.network.tailscale.TailscaleProvider
import app.pocketpilot.network.wireguard.WireGuardProvider
import app.pocketpilot.onboarding.OnboardingStore
import app.pocketpilot.onboarding.SetupActions
import app.pocketpilot.onboarding.SetupState
import app.pocketpilot.onboarding.setupSteps
import app.pocketpilot.security.BiometricGate
import app.pocketpilot.security.ScreenshotLock
import app.pocketpilot.security.ShellSwitch
import app.pocketpilot.security.ToolModeStore
import app.pocketpilot.server.LocalTokenStore
import app.pocketpilot.server.PocketPilotService
import app.pocketpilot.server.http.McpHttpServer
import app.pocketpilot.server.http.ServerState
import app.pocketpilot.server.oauth.AuthorizationServer
import app.pocketpilot.ui.HomeProvider
import app.pocketpilot.ui.HomeProviderState
import app.pocketpilot.ui.HomeScreen
import app.pocketpilot.ui.HomeTelemetry
import app.pocketpilot.ui.PendingApproval
import app.pocketpilot.ui.RemoteActions
import app.pocketpilot.ui.claudeCodeCommand
import app.pocketpilot.ui.copySecret
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.YearMonth
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var policyProfile: PolicyProfile

    @Inject lateinit var ruleStore: RuleStore

    @Inject lateinit var server: McpHttpServer

    @Inject lateinit var tokens: LocalTokenStore

    @Inject lateinit var secrets: SecretStore

    @Inject lateinit var capabilityGraph: CapabilityGraph

    @Inject lateinit var shizuku: ShizukuConnection

    @Inject lateinit var tailscale: TailscaleProvider

    @Inject lateinit var oauth: AuthorizationServer

    @Inject lateinit var certificates: CertificateManager

    @Inject lateinit var wireGuard: WireGuardProvider

    @Inject lateinit var agent: AgentCoordinator

    @Inject lateinit var handoff: ScreenHandoff

    @Inject lateinit var shellSwitch: ShellSwitch

    @Inject lateinit var controlOverlay: ControlOverlay

    @Inject lateinit var biometricGate: BiometricGate

    @Inject lateinit var screenshotLock: ScreenshotLock

    @Inject lateinit var shizukuShell: ShizukuShellRunner

    @Inject lateinit var appScope: CoroutineScope

    @Inject lateinit var sharedFolders: SafFileStore

    @Inject lateinit var audit: AuditSink

    @Inject lateinit var onboarding: OnboardingStore

    @Inject lateinit var approvals: ApprovalCenter

    @Inject lateinit var registry: ToolRegistry

    @Inject lateinit var toolModes: ToolModeStore

    @Inject lateinit var automations: AutomationRuntime

    @Inject lateinit var homeAssistant: HomeAssistant

    @Inject lateinit var providerHealth: ProviderHealth

    @Inject lateinit var deviceInfo: DeviceInfoSource

    /** Whether a Home Assistant token is saved; the secret store has no listener. */
    private val homeAssistantToken = mutableStateOf(false)

    private val resumes = mutableIntStateOf(0)

    /** Set when setup sends the owner to remote access, which lives under Settings. */
    private var homeOnSettings by mutableStateOf(false)

    /** Counts requests from notifications to show the Automations tab. */
    private val openAutomations = mutableIntStateOf(0)

    /** Counts requests from reply notifications to show the Chat tab. */
    private val openChat = mutableIntStateOf(0)

    /** A proposal notification asked for the Proposals list. */
    private var openProposals by mutableStateOf(false)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOpen(intent)
    }

    /** Runs [onAccepted] once the owner confirms, when the rule runs shell commands; other rules need no check. */
    private fun acceptGated(
        rule: Rule?,
        onAccepted: () -> Unit,
    ) {
        if (rule?.usesShell() != true) return onAccepted()
        biometricGate.authenticate(
            this,
            getString(R.string.approvals_biometric_title),
            getString(R.string.automation_accept_shell),
            onAccepted,
        )
    }

    /** A rule shared from another phone as a .pocketpilot file: it becomes a proposal to accept. */
    private fun receiveSharedRule(intent: Intent) {
        val uri =
            when (intent.action) {
                Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                Intent.ACTION_VIEW -> intent.data
                else -> null
            } ?: return
        val json = runCatching { contentResolver.openInputStream(uri)?.use(::readCapped) }.getOrNull()
        val rule = json?.let { parseSharedRule(it) { ruleStore.newId() } }
        if (rule == null) {
            Toast.makeText(this, R.string.app_shared_rule_unreadable, Toast.LENGTH_LONG).show()
            return
        }
        if (RuleValidator.problems(rule, policyProfile.shellExecAvailable).isNotEmpty()) {
            Toast.makeText(this, R.string.app_shared_rule_invalid, Toast.LENGTH_LONG).show()
            return
        }
        ruleStore.addShared(rule)
        openProposals = true
        openAutomations.intValue++
    }

    /** The shared file's text, or null when it is larger than [MAX_SHARED_RULE_BYTES]. */
    private fun readCapped(input: InputStream): String? {
        val bytes = ByteArrayOutputStream()
        val chunk = ByteArray(8 * 1024)
        while (true) {
            val read = input.read(chunk)
            if (read < 0) break
            if (bytes.size() + read > MAX_SHARED_RULE_BYTES) return null
            bytes.write(chunk, 0, read)
        }
        return bytes.toString(Charsets.UTF_8.name())
    }

    private fun handleOpen(intent: Intent?) {
        intent?.let(::receiveSharedRule)
        when (intent?.getStringExtra(EXTRA_OPEN)) {
            OPEN_PROPOSALS -> {
                openProposals = true
                openAutomations.intValue++
            }

            OPEN_AUTOMATIONS -> {
                openAutomations.intValue++
            }

            OPEN_CHAT -> {
                openChat.intValue++
            }
        }
    }

    /** The quick prompt on Home: answers in the running chat, or starts one on the first model. */
    private fun askAgent(text: String) {
        val prompt = text.trim()
        if (prompt.isEmpty()) return
        if (!agent.send(prompt)) {
            val first =
                agent.profiles.profiles.value
                    .firstOrNull() ?: return
            agent.start(prompt, first.id, allowSettings = false, useFallbacks = true, conversation = true)
            PocketPilotService.start(this)
        }
        openChat.intValue++
    }

    /** Agent mode's models in the owner's fallback order, with how each last answered. */
    @Composable
    private fun homeProviders(): List<HomeProvider> {
        val profiles =
            agent.profiles.profiles
                .collectAsStateWithLifecycle()
                .value
        val health = providerHealth.status.collectAsStateWithLifecycle().value
        return profiles.map { profile ->
            val status = health[profile.name]
            HomeProvider(
                name = profile.name,
                state =
                    when (status?.state) {
                        ProviderState.READY -> HomeProviderState.READY
                        ProviderState.LIMITED -> HomeProviderState.LIMITED
                        ProviderState.DOWN -> HomeProviderState.DOWN
                        ProviderState.UNKNOWN, null -> HomeProviderState.UNKNOWN
                    },
                retryAtMillis = status?.retryAtMillis,
            )
        }
    }

    /** Battery and network, read again every half minute while the app is in front. */
    @Composable
    private fun homeTelemetry(): HomeTelemetry? {
        val owner = LocalLifecycleOwner.current
        return produceState<HomeTelemetry?>(initialValue = null, owner) {
            owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    runCatching { deviceInfo.read() }.onSuccess { info ->
                        value =
                            HomeTelemetry(
                                batteryPercent = info.battery.levelPercent,
                                charging = info.battery.charging,
                                transport = info.network.transport,
                                connected = info.network.connected,
                            )
                    }
                    delay(TELEMETRY_REFRESH_MS)
                }
            }
        }.value
    }

    override fun onResume() {
        super.onResume()
        resumes.intValue++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The launching intent is redelivered when the activity is recreated, so handle it only on a fresh start.
        if (savedInstanceState == null) handleOpen(intent)
        val crashReports = (application as PocketPilotApplication).crashReports
        crashReports.load()
        certificates.load()
        val ownNetworkActions =
            OwnNetworkActions(
                saveCertificate = certificates::configure,
                requestCertificate = certificates::request,
                setFollowRelay = certificates::setFollowRelay,
                configureWireGuard = wireGuard::configure,
                setWireGuard = { on -> if (on) startNetwork(wireGuard::start) else wireGuard.stop() },
                forgetWireGuard = wireGuard::logout,
                wireGuardLog = { wireGuard.logs() + "\n\n--- Certificate ---\n" + certificates.logs() },
            )
        val agentActions =
            AgentActions(
                pause = { agent.pause() },
                resume = { agent.resume() },
                stop = { agent.stop() },
                saveProfile = agent::saveProfile,
                deleteProfile = agent::deleteProfile,
                keyCount = agent::keyCount,
                moveUp = agent::moveUp,
                listModels = agent::listModels,
            )
        val chatActions =
            ChatActions(
                start = { text, profileId, allowSettings, useFallbacks ->
                    agent.start(text, profileId, allowSettings, useFallbacks, conversation = true)
                    // The service keeps the process alive while the agent works in other apps.
                    PocketPilotService.start(this)
                },
                send = agent::send,
                pause = { agent.pause() },
                resume = { agent.resume() },
                stop = { agent.stop() },
                endChat = agent::endChat,
                deleteChats = agent::deleteChats,
                deleteAllChats = agent::deleteAllChats,
                openModels = {},
            )
        val automationActions =
            AutomationsActions(
                newId = automations.store::newId,
                save = { rule ->
                    // Saving a proposal accepts it, so it gets the same check as accepting it.
                    if (automations.store.rule(rule.id)?.pending == true) {
                        acceptGated(rule) { automations.store.saveAccepted(rule) }
                    } else {
                        automations.store.upsert(rule)
                    }
                },
                delete = automations.store::delete,
                setEnabled = automations.store::setEnabled,
                setPinned = automations.store::setPinned,
                run = { rule -> automations.engine.start(rule, getString(R.string.automation_reason_run)) },
                test = { rule -> automations.engine.start(rule, getString(R.string.automation_reason_test), checkConstraints = false) },
                stop = automations.engine::stop,
                accept = { id ->
                    acceptGated(automations.store.rule(id)) { automations.store.accept(id) }
                },
                decline = automations.store::delete,
                setSettings = automations.store::setSettings,
                loadApps = automations::loadApps,
                setHomeAssistant = { url, token ->
                    val settings = automations.store.data.value.settings
                    automations.store.setSettings(settings.copy(homeAssistantUrl = url.ifBlank { null }))
                    if (token.isNotBlank()) homeAssistant.saveToken(token)
                    homeAssistantToken.value = homeAssistant.hasToken
                    homeAssistant.reconnect()
                },
            )
        homeAssistantToken.value = homeAssistant.hasToken
        setContent {
            // The policy never lets the agent act on PocketPilot itself, so step out of its way when a
            // chat needs the screen.
            LaunchedEffect(Unit) { handoff.requests.collect { moveTaskToBack(true) } }
            // The foreground service notification needs this permission on Android 13+; the server
            // runs either way, the notification is just hidden without it.
            val notificationPermission =
                rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                    PocketPilotService.start(this)
                }
            val folderPicker =
                rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
                    tree?.let { runCatching { sharedFolders.add(it) } }
                }
            val serverState = server.serverState.collectAsStateWithLifecycle().value
            val token = tokens.token.collectAsStateWithLifecycle().value
            val eventKey = remember { EventKey.get(secrets) }
            val shizukuState = shizuku.state.collectAsStateWithLifecycle().value
            val accessibilityOn = A11yBridge.connected.collectAsStateWithLifecycle().value
            val tailscaleState = tailscale.state.collectAsStateWithLifecycle().value
            val wireGuardState = wireGuard.state.collectAsStateWithLifecycle().value
            val auditEvents = audit.recent.collectAsStateWithLifecycle().value
            val approvalItems = approvals.items.collectAsStateWithLifecycle().value
            val tools = remember { registry.specs }
            val automationData =
                automations.store.data
                    .collectAsStateWithLifecycle()
                    .value
            val automationState =
                AutomationsState(
                    rules = automationData.rules,
                    runs = automationData.runs,
                    running =
                        automations.engine.running
                            .collectAsStateWithLifecycle()
                            .value,
                    settings = automationData.settings,
                    agentSpentThisMonthUsd = automationData.agentSpendUsd[YearMonth.now().toString()] ?: 0.0,
                    apps = automations.apps.collectAsStateWithLifecycle().value,
                    tools = remember(tools) { tools.map { it.name }.sorted() },
                    sideload = policyProfile.shellExecAvailable,
                    homeAssistantToken = homeAssistantToken.value,
                )
            // Notification and battery grants have no listener; they are read again on every resume.
            resumes.intValue
            val setup =
                setupSteps(
                    SetupState(
                        notificationsAllowed = getSystemService(NotificationManager::class.java).areNotificationsEnabled(),
                        batteryUnrestricted = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName),
                        accessibilityOn = accessibilityOn,
                        shizuku = shizukuState,
                        notificationAccess = ListenerNotificationSource.available.collectAsStateWithLifecycle().value,
                        remoteConnected = tailscaleState is NetworkState.Connected || wireGuardState is NetworkState.Connected,
                        audit = auditEvents,
                    ),
                    SetupActions(
                        allowNotifications = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                startActivity(
                                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
                                )
                            }
                        },
                        unrestrictBattery = ::unrestrictBattery,
                        turnOnAccessibility = ::turnOnAccessibility,
                        setUpShizuku = { setUpShizuku(shizukuState) },
                        allowNotificationAccess = ::allowNotifications,
                        openRemoteAccess = {
                            homeOnSettings = true
                            onboarding.setFinished(true)
                        },
                        copyClaudeCodeCommand = {
                            val url = (serverState as? ServerState.Running)?.url ?: DEFAULT_LOCAL_URL
                            copySecret(this@MainActivity, claudeCodeCommand(url, token))
                        },
                    ),
                )
            PocketPilotTheme {
                if (!onboarding.finished.collectAsStateWithLifecycle().value) {
                    OnboardingScreen(
                        steps = setup,
                        index = onboarding.index.collectAsStateWithLifecycle().value,
                        onIndexChange = onboarding::setIndex,
                        onFinish = { onboarding.setFinished(true) },
                    )
                } else {
                    HomeScreen(
                        startOnSettings = homeOnSettings,
                        setupSteps = setup,
                        onOpenSetup = { onboarding.setFinished(false) },
                        policyProfile = policyProfile,
                        versionName = BuildConfig.VERSION_NAME,
                        serverState = serverState,
                        accessibilityOn = accessibilityOn,
                        capabilities = capabilityGraph.state.collectAsStateWithLifecycle().value,
                        shizukuState = shizukuState,
                        crashReport = crashReports.report.collectAsStateWithLifecycle().value,
                        onDismissCrash = crashReports::dismiss,
                        remoteState = tailscaleState,
                        publicState = tailscale.publicState.collectAsStateWithLifecycle().value,
                        clients =
                            oauth.clients.collectAsStateWithLifecycle().value.map { client ->
                                ClientRow(
                                    client.id,
                                    client.name,
                                    client.redirectHost,
                                    client.scopes.map { it.value }.sorted(),
                                    client.lastUsedAtMillis,
                                )
                            },
                        token = token,
                        eventKey = eventKey,
                        onStart = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                PocketPilotService.start(this)
                            }
                        },
                        onStop = { PocketPilotService.stop(this) },
                        onRotateToken = tokens::rotate,
                        onOpenAccessibilitySettings = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                        onOpenAppInfo = {
                            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                        },
                        onGrantShizuku = shizuku::requestPermission,
                        onAllowNotifications = ::allowNotifications,
                        sharedFolders = sharedFolders.sharedFolders.collectAsStateWithLifecycle().value,
                        onAddFolder = { folderPicker.launch(null) },
                        onRemoveFolder = { sharedFolders.remove(it.uri) },
                        remoteActions =
                            RemoteActions(
                                turnOn = {
                                    tailscale.start()
                                    PocketPilotService.start(this)
                                },
                                turnOff = tailscale::stop,
                                // Only the https sign-in page is opened, never another scheme the library reports.
                                signIn = { url ->
                                    if (url.startsWith("https://")) startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                },
                                signOut = tailscale::logout,
                                setPublic = tailscale::setPublic,
                                logs = tailscale::logs,
                            ),
                        onRevokeClient = oauth::revokeClient,
                        onKillSwitch = {
                            oauth.revokeAll()
                            tailscale.setPublic(false)
                            // A relay makes the phone public, so the kill switch closes WireGuard too.
                            wireGuard.stop()
                        },
                        ownNetworkCard = {
                            OwnNetworkCard(
                                certificate = certificates.state.collectAsStateWithLifecycle().value,
                                wireGuard = wireGuardState,
                                wireGuardSetup = wireGuard.setup.collectAsStateWithLifecycle().value,
                                actions = ownNetworkActions,
                            )
                        },
                        agentCard = {
                            AgentCard(
                                profiles =
                                    agent.profiles.profiles
                                        .collectAsStateWithLifecycle()
                                        .value,
                                current = agent.current.collectAsStateWithLifecycle().value,
                                history = agent.history.collectAsStateWithLifecycle().value,
                                actions = agentActions,
                            )
                        },
                        shellEnabled = shellSwitch.enabled.collectAsStateWithLifecycle().value,
                        // Loosening a safeguard needs the owner, like approving an action does.
                        onShellChange = { on -> if (on) withOwner { shellSwitch.set(true) } else shellSwitch.set(false) },
                        clientsStopped = controlOverlay.clientsStopped.collectAsStateWithLifecycle().value,
                        onAllowClients = controlOverlay::allowAgain,
                        biometricApprovals = biometricGate.enabled.collectAsStateWithLifecycle().value,
                        screenLockMissing = !biometricGate.lockAvailable(),
                        secretsLost = secrets.lost().isNotEmpty(),
                        onBiometricChange = { on -> if (on) biometricGate.set(true) else withOwner { biometricGate.set(false) } },
                        secretsLocked = screenshotLock.enabled.collectAsStateWithLifecycle().value,
                        // Letting screenshots show the token needs the owner, like loosening the biometric lock does.
                        onSecretsLockChange = { on -> if (on) screenshotLock.set(true) else withOwner { screenshotLock.set(false) } },
                        wireGuardState = wireGuardState,
                        auditEvents = auditEvents,
                        approvals = approvalItems.map(::pendingApproval),
                        onApprove = { id -> approvalItems.firstOrNull { it.id == id }?.let(::approve) },
                        onDecline = { id -> approvalItems.firstOrNull { it.id == id }?.let(::decline) },
                        tools = tools,
                        toolModes = toolModes.modes.collectAsStateWithLifecycle().value,
                        onToolModeChange = { tool, mode -> tools.firstOrNull { it.name == tool }?.let { setToolMode(it, mode) } },
                        protectedApps = AppPolicy.DEFAULT_DENY.sorted(),
                        automationsContent = {
                            AutomationsScreen(
                                state = automationState,
                                actions = automationActions,
                                openProposals = openProposals,
                                onProposalsOpened = { openProposals = false },
                            )
                        },
                        automationsBadge = automationData.rules.count { it.pending },
                        pinnedRules =
                            automationData.rules
                                .filter { it.pinned && !it.pending }
                                .map { PinnedRuleUi(it.id, it.name, it.enabled) },
                        onPinnedToggle = automations.store::setEnabled,
                        openAutomations = openAutomations.intValue,
                        providers = homeProviders(),
                        telemetry = homeTelemetry(),
                        onAgentPrompt = ::askAgent,
                        chatContent = { openModels ->
                            AgentChat(
                                profiles =
                                    agent.profiles.profiles
                                        .collectAsStateWithLifecycle()
                                        .value,
                                current = agent.current.collectAsStateWithLifecycle().value,
                                history = agent.history.collectAsStateWithLifecycle().value,
                                actions = chatActions.copy(openModels = openModels),
                            )
                        },
                        openChat = openChat.intValue,
                    )
                }
            }
        }
    }

    private fun pendingApproval(item: ApprovalItem): PendingApproval =
        when (item) {
            is ApprovalItem.Consent -> {
                PendingApproval(
                    id = item.id,
                    title = getString(R.string.approvals_consent_text, item.request.clientName),
                    detail = item.request.redirectHost,
                    tier = null,
                    opensScreen = true,
                )
            }

            is ApprovalItem.Confirm -> {
                PendingApproval(
                    id = item.id,
                    title = getString(R.string.approvals_confirm_text, item.request.principal, item.request.tool),
                    detail = item.request.detail,
                    tier = item.request.riskTier,
                )
            }
        }

    /** Allows a tool call after the biometric lock; consent opens the full screen, where the owner picks permissions. */
    private fun approve(item: ApprovalItem) {
        when (item) {
            is ApprovalItem.Consent -> {
                startActivity(Intent(this, ApprovalActivity::class.java))
            }

            is ApprovalItem.Confirm -> {
                biometricGate.authenticate(
                    this,
                    getString(R.string.approvals_biometric_title),
                    getString(R.string.approvals_confirm_text, item.request.principal, item.request.tool),
                ) { approvals.answerConfirm(item.id, true) }
            }
        }
    }

    private fun decline(item: ApprovalItem) {
        when (item) {
            is ApprovalItem.Consent -> approvals.answerConsent(item.id, null)
            is ApprovalItem.Confirm -> approvals.answerConfirm(item.id, false)
        }
    }

    /** Tightening a tool applies at once; loosening one is a safeguard change, so it needs the owner. */
    private fun setToolMode(
        spec: ToolSpec,
        mode: ToolMode?,
    ) {
        val before = ToolModes.effectiveMode(toolModes, spec)
        val after = mode?.takeIf { it in ToolModes.allowedModes(spec) } ?: ToolModes.defaultMode(spec)
        if (after.ordinal < before.ordinal) withOwner { toolModes.set(spec.name, mode) } else toolModes.set(spec.name, mode)
    }

    /** Exempts PocketPilot from battery optimization through Shizuku, or opens the system list. */
    private fun unrestrictBattery() =
        viaShizukuOr("cmd deviceidle whitelist +$packageName", Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))

    /** Adds PocketPilot's Accessibility service to the enabled list through Shizuku, or opens Accessibility settings. */
    private fun turnOnAccessibility() {
        val service = ComponentName(this, PocketPilotA11yService::class.java).flattenToString()
        val command =
            "list=\$(settings get secure enabled_accessibility_services); " +
                "case \"\$list\" in *$service*) ;; null|'') list=$service ;; *) list=\"\$list:$service\" ;; esac; " +
                "settings put secure enabled_accessibility_services \"\$list\" && settings put secure accessibility_enabled 1"
        viaShizukuOr(command, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    /** Asks Shizuku for permission when it runs, else opens the Shizuku app or its download page. */
    private fun setUpShizuku(state: ShizukuState) {
        if (state == ShizukuState.PERMISSION_NEEDED) {
            shizuku.requestPermission()
            return
        }
        val app = packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
        startActivity(app ?: Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_DOWNLOAD)))
    }

    private fun viaShizukuOr(
        command: String,
        fallback: Intent,
    ) {
        appScope.launch(Dispatchers.Main) {
            val done =
                shizukuShell.available.value &&
                    runCatching { shizukuShell.run(command, SHIZUKU_TIMEOUT_MS).exitCode == 0 }.getOrDefault(false)
            if (done) resumes.intValue++ else startActivity(fallback)
        }
    }

    /** Allows the notification listener through Shizuku, or opens the system page where the owner allows it. */
    private fun allowNotifications() {
        val component = ListenerNotificationSource.component(this).flattenToString()
        viaShizukuOr("cmd notification allow_listener $component", Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    /** Runs [action] once the owner passes the biometric lock. */
    private fun withOwner(action: () -> Unit) =
        biometricGate.authenticate(
            this,
            getString(R.string.approvals_biometric_title),
            getString(R.string.biometric_setting_subtitle),
            action,
        )

    /** Turns a network on and makes sure the foreground service that keeps it running is up. */
    private fun startNetwork(start: () -> Unit) {
        start()
        PocketPilotService.start(this)
    }

    companion object {
        /** Which part of the app a notification opens: [OPEN_AUTOMATIONS], [OPEN_PROPOSALS] or [OPEN_CHAT]. */
        const val EXTRA_OPEN = "open"
        const val OPEN_AUTOMATIONS = "automations"
        const val OPEN_CHAT = "chat"
        const val OPEN_PROPOSALS = "proposals"
        private const val MAX_SHARED_RULE_BYTES = 256 * 1024
        private const val SHIZUKU_TIMEOUT_MS = 10_000L
        private const val TELEMETRY_REFRESH_MS = 30_000L
        private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        private const val SHIZUKU_DOWNLOAD = "https://shizuku.rikka.app/download/"
        private const val DEFAULT_LOCAL_URL = "http://127.0.0.1:8765/mcp"
    }
}
