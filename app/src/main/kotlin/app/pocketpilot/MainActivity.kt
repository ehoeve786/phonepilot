package app.pocketpilot

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pocketpilot.agent.AgentCoordinator
import app.pocketpilot.capability.accessibility.A11yBridge
import app.pocketpilot.capability.shizuku.ShizukuConnection
import app.pocketpilot.core.capabilities.CapabilityGraph
import app.pocketpilot.core.model.PolicyProfile
import app.pocketpilot.feature.agent.AgentActions
import app.pocketpilot.feature.agent.AgentCard
import app.pocketpilot.feature.clients.ClientRow
import app.pocketpilot.feature.network.OwnNetworkActions
import app.pocketpilot.feature.network.OwnNetworkCard
import app.pocketpilot.network.certificates.CertificateManager
import app.pocketpilot.network.tailscale.TailscaleProvider
import app.pocketpilot.network.wireguard.WireGuardProvider
import app.pocketpilot.security.ShellSwitch
import app.pocketpilot.server.LocalTokenStore
import app.pocketpilot.server.PocketPilotService
import app.pocketpilot.server.http.McpHttpServer
import app.pocketpilot.server.oauth.AuthorizationServer
import app.pocketpilot.ui.HomeScreen
import app.pocketpilot.ui.PocketPilotTheme
import app.pocketpilot.ui.RemoteActions
import dagger.hilt.android.AndroidEntryPoint
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
                start = { goal, profileId, allowSettings ->
                    agent.start(goal, profileId, allowSettings)
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
                listModels = agent::listModels,
            )
        setContent {
            // The foreground service notification needs this permission on Android 13+; the server
            // runs either way, the notification is just hidden without it.
            val notificationPermission =
                rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                    PocketPilotService.start(this)
                }
            PocketPilotTheme {
                HomeScreen(
                    policyProfile = policyProfile,
                    versionName = BuildConfig.VERSION_NAME,
                    serverState = server.serverState.collectAsStateWithLifecycle().value,
                    accessibilityOn = A11yBridge.connected.collectAsStateWithLifecycle().value,
                    capabilities = capabilityGraph.state.collectAsStateWithLifecycle().value,
                    shizukuState = shizuku.state.collectAsStateWithLifecycle().value,
                    crashReport = crashReports.report.collectAsStateWithLifecycle().value,
                    onDismissCrash = crashReports::dismiss,
                    remoteState = tailscale.state.collectAsStateWithLifecycle().value,
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
                    token = tokens.token.collectAsStateWithLifecycle().value,
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
                            wireGuard = wireGuard.state.collectAsStateWithLifecycle().value,
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
                    onShellChange = shellSwitch::set,
                )
            }
        }
    }

    /** Turns a network on and makes sure the foreground service that keeps it running is up. */
    private fun startNetwork(start: () -> Unit) {
        start()
        PocketPilotService.start(this)
    }
}
