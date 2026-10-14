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
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pocketpilot.agent.AgentCoordinator
import app.pocketpilot.capability.accessibility.A11yBridge
import app.pocketpilot.capability.accessibility.PocketPilotA11yService
import app.pocketpilot.capability.files.SafFileStore
import app.pocketpilot.capability.notifications.ListenerNotificationSource
import app.pocketpilot.capability.shizuku.ShizukuConnection
import app.pocketpilot.capability.shizuku.ShizukuShellRunner
import app.pocketpilot.capability.shizuku.ShizukuState
import app.pocketpilot.control.ControlOverlay
import app.pocketpilot.core.audit.AuditSink
import app.pocketpilot.core.capabilities.CapabilityGraph
import app.pocketpilot.core.model.PolicyProfile
import app.pocketpilot.feature.agent.AgentActions
import app.pocketpilot.feature.agent.AgentCard
import app.pocketpilot.feature.clients.ClientRow
import app.pocketpilot.feature.network.OwnNetworkActions
import app.pocketpilot.feature.network.OwnNetworkCard
import app.pocketpilot.feature.onboarding.OnboardingScreen
import app.pocketpilot.network.api.NetworkState
import app.pocketpilot.network.certificates.CertificateManager
import app.pocketpilot.network.tailscale.TailscaleProvider
import app.pocketpilot.network.wireguard.WireGuardProvider
import app.pocketpilot.onboarding.OnboardingStore
import app.pocketpilot.onboarding.SetupActions
import app.pocketpilot.onboarding.SetupState
import app.pocketpilot.onboarding.setupSteps
import app.pocketpilot.security.BiometricGate
import app.pocketpilot.security.ShellSwitch
import app.pocketpilot.server.LocalTokenStore
import app.pocketpilot.server.PocketPilotService
import app.pocketpilot.server.http.McpHttpServer
import app.pocketpilot.server.http.ServerState
import app.pocketpilot.server.oauth.AuthorizationServer
import app.pocketpilot.ui.HomeScreen
import app.pocketpilot.ui.PocketPilotTheme
import app.pocketpilot.ui.RemoteActions
import app.pocketpilot.ui.claudeCodeCommand
import app.pocketpilot.ui.copySecret
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var policyProfile: PolicyProfile

    @Inject lateinit var server: McpHttpServer

    @Inject lateinit var tokens: LocalTokenStore

    @Inject lateinit var capabilityGraph: CapabilityGraph

    @Inject lateinit var shizuku: ShizukuConnection

    @Inject lateinit var tailscale: TailscaleProvider

    @Inject lateinit var oauth: AuthorizationServer

    @Inject lateinit var certificates: CertificateManager

    @Inject lateinit var wireGuard: WireGuardProvider

    @Inject lateinit var agent: AgentCoordinator

    @Inject lateinit var shellSwitch: ShellSwitch

    @Inject lateinit var controlOverlay: ControlOverlay

    @Inject lateinit var biometricGate: BiometricGate

    @Inject lateinit var shizukuShell: ShizukuShellRunner

    @Inject lateinit var appScope: CoroutineScope

    @Inject lateinit var sharedFolders: SafFileStore

    @Inject lateinit var audit: AuditSink

    @Inject lateinit var onboarding: OnboardingStore

    private val resumes = mutableIntStateOf(0)

    /** Set when setup sends the owner to remote access, which lives under Settings. */
    private var homeOnSettings by mutableStateOf(false)

    override fun onResume() {
        super.onResume()
        resumes.intValue++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
                start = { goal, profileId, allowSettings, useFallbacks ->
                    agent.start(goal, profileId, allowSettings, useFallbacks)
                    // The service keeps the process alive while the agent works in other apps, and the
                    // policy never lets the agent act on PocketPilot itself, so step out of its way.
                    PocketPilotService.start(this)
                    moveTaskToBack(true)
                },
                pause = { agent.pause() },
                resume = { agent.resume() },
                stop = { agent.stop() },
                saveProfile = agent::saveProfile,
                deleteProfile = agent::deleteProfile,
                keyCount = agent::keyCount,
                moveUp = agent::moveUp,
                listModels = agent::listModels,
            )
        setContent {
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
            val shizukuState = shizuku.state.collectAsStateWithLifecycle().value
            val accessibilityOn = A11yBridge.connected.collectAsStateWithLifecycle().value
            val tailscaleState = tailscale.state.collectAsStateWithLifecycle().value
            val wireGuardState = wireGuard.state.collectAsStateWithLifecycle().value
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
                        audit = audit.recent.collectAsStateWithLifecycle().value,
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
                                signIn = { url -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) },
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
                        onBiometricChange = { on -> if (on) biometricGate.set(true) else withOwner { biometricGate.set(false) } },
                    )
                }
            }
        }
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

    private companion object {
        const val SHIZUKU_TIMEOUT_MS = 10_000L
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        const val SHIZUKU_DOWNLOAD = "https://shizuku.rikka.app/download/"
        const val DEFAULT_LOCAL_URL = "http://127.0.0.1:8765/mcp"
    }
}
