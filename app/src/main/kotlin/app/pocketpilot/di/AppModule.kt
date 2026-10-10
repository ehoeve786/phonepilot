package app.pocketpilot.di

import android.content.Context
import app.pocketpilot.BuildConfig
import app.pocketpilot.approvals.ApprovalCenter
import app.pocketpilot.capability.accessibility.A11yBridge
import app.pocketpilot.capability.accessibility.A11yInputController
import app.pocketpilot.capability.accessibility.A11yScreenCapturer
import app.pocketpilot.capability.accessibility.A11yScreenReader
import app.pocketpilot.capability.api.DeviceInfoSource
import app.pocketpilot.capability.api.apps.AppController
import app.pocketpilot.capability.api.screen.InputController
import app.pocketpilot.capability.api.screen.ScreenCapturer
import app.pocketpilot.capability.api.screen.ScreenReader
import app.pocketpilot.capability.apps.PackageAppController
import app.pocketpilot.capability.device.AndroidDeviceInfoSource
import app.pocketpilot.capability.screencapture.BitmapImageEncoder
import app.pocketpilot.capability.shizuku.ShizukuAppController
import app.pocketpilot.capability.shizuku.ShizukuConnection
import app.pocketpilot.capability.shizuku.ShizukuInputController
import app.pocketpilot.capability.shizuku.ShizukuScreenCapturer
import app.pocketpilot.capability.shizuku.ShizukuScreenReader
import app.pocketpilot.core.audit.AuditSink
import app.pocketpilot.core.audit.InMemoryAuditSink
import app.pocketpilot.core.capabilities.CapabilityGraph
import app.pocketpilot.core.capabilities.ResolvingAppController
import app.pocketpilot.core.capabilities.ResolvingInputController
import app.pocketpilot.core.capabilities.ResolvingScreenCapturer
import app.pocketpilot.core.capabilities.ResolvingScreenReader
import app.pocketpilot.core.imaging.ImagePipeline
import app.pocketpilot.core.model.CapabilityId
import app.pocketpilot.core.model.PolicyProfile
import app.pocketpilot.core.orchestrator.CallDispatcher
import app.pocketpilot.core.orchestrator.SessionFactory
import app.pocketpilot.core.orchestrator.ToolHandler
import app.pocketpilot.core.orchestrator.ToolRegistry
import app.pocketpilot.core.policy.AppPolicy
import app.pocketpilot.core.policy.PolicyEngine
import app.pocketpilot.core.tools.AppCurrentTool
import app.pocketpilot.core.tools.AppLaunchTool
import app.pocketpilot.core.tools.AppListTool
import app.pocketpilot.core.tools.AppOpenUrlTool
import app.pocketpilot.core.tools.DeviceInfoTool
import app.pocketpilot.core.tools.ScreenCaptureTool
import app.pocketpilot.core.tools.ScreenFindTool
import app.pocketpilot.core.tools.ScreenSnapshotTool
import app.pocketpilot.core.tools.ScreenWaitForChangeTool
import app.pocketpilot.core.tools.ScreenWaitForTool
import app.pocketpilot.core.tools.UiGlobalActionTool
import app.pocketpilot.core.tools.UiLongPressTool
import app.pocketpilot.core.tools.UiPressKeyTool
import app.pocketpilot.core.tools.UiSwipeTool
import app.pocketpilot.core.tools.UiTapTool
import app.pocketpilot.core.tools.UiTypeTextTool
import app.pocketpilot.network.api.NetworkState
import app.pocketpilot.network.api.SecretStore
import app.pocketpilot.network.certificates.CertificateManager
import app.pocketpilot.network.tailscale.TailscaleProvider
import app.pocketpilot.network.wireguard.WireGuardProvider
import app.pocketpilot.network.zerotier.ZeroTierProvider
import app.pocketpilot.security.KeystoreSecretStore
import app.pocketpilot.server.LocalTokenStore
import app.pocketpilot.server.http.McpHttpServer
import app.pocketpilot.server.mcp.McpServerFactory
import app.pocketpilot.server.oauth.AuthorizationServer
import app.pocketpilot.server.oauth.FileOAuthStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.ElementsIntoSet
import dagger.multibindings.IntoSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import javax.inject.Singleton

/**
 * The app module is the only place that binds capability implementations to their interfaces
 * (spec section 2), so any backend can be swapped or faked.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun deviceInfoSource(
        @ApplicationContext context: Context,
    ): DeviceInfoSource = AndroidDeviceInfoSource(context)

    @Provides
    @IntoSet
    fun deviceInfoTool(source: DeviceInfoSource): ToolHandler = DeviceInfoTool(source)

    /** Lives as long as the process; backs the availability flows of every backend. */
    @Provides
    @Singleton
    fun appScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun a11yScreenReader(): A11yScreenReader = A11yScreenReader()

    @Provides
    @Singleton
    fun a11yInputController(reader: A11yScreenReader): A11yInputController = A11yInputController(reader)

    @Provides
    @Singleton
    fun a11yScreenCapturer(): A11yScreenCapturer = A11yScreenCapturer()

    @Provides
    @Singleton
    fun packageAppController(
        @ApplicationContext context: Context,
    ): PackageAppController = PackageAppController(context)

    @Provides
    @Singleton
    fun shizukuConnection(
        @ApplicationContext context: Context,
    ): ShizukuConnection = ShizukuConnection(context)

    @Provides
    @Singleton
    fun shizukuScreenReader(
        shizuku: ShizukuConnection,
        scope: CoroutineScope,
    ): ShizukuScreenReader = ShizukuScreenReader(shizuku, scope)

    @Provides
    @Singleton
    fun shizukuInputController(
        shizuku: ShizukuConnection,
        reader: ShizukuScreenReader,
        scope: CoroutineScope,
    ): ShizukuInputController = ShizukuInputController(shizuku, reader, scope)

    @Provides
    @Singleton
    fun shizukuScreenCapturer(
        shizuku: ShizukuConnection,
        scope: CoroutineScope,
    ): ShizukuScreenCapturer = ShizukuScreenCapturer(shizuku, scope)

    @Provides
    @Singleton
    fun shizukuAppController(
        shizuku: ShizukuConnection,
        scope: CoroutineScope,
    ): ShizukuAppController = ShizukuAppController(shizuku, scope)

    // Spec section 3 backend order: READ_UI prefers Accessibility, INJECT_INPUT and CAPTURE_SCREEN
    // prefer Shizuku, LAUNCH_APPS prefers package manager intents.

    @Provides
    @Singleton
    fun resolvingScreenReader(
        a11y: A11yScreenReader,
        shizuku: ShizukuScreenReader,
        scope: CoroutineScope,
    ): ResolvingScreenReader = ResolvingScreenReader(listOf(a11y, shizuku), scope)

    @Provides
    fun screenReader(reader: ResolvingScreenReader): ScreenReader = reader

    @Provides
    @Singleton
    fun inputController(
        shizuku: ShizukuInputController,
        a11y: A11yInputController,
        reader: ResolvingScreenReader,
        scope: CoroutineScope,
    ): InputController = ResolvingInputController(listOf(shizuku, a11y), reader, scope)

    @Provides
    @Singleton
    fun screenCapturer(
        shizuku: ShizukuScreenCapturer,
        a11y: A11yScreenCapturer,
        scope: CoroutineScope,
    ): ScreenCapturer = ResolvingScreenCapturer(listOf(shizuku, a11y), scope)

    @Provides
    @Singleton
    fun imagePipeline(): ImagePipeline = ImagePipeline(BitmapImageEncoder())

    /** Activity starts from the background need the Accessibility service running, else Shizuku launches. */
    @Provides
    @Singleton
    fun appController(
        packageManager: PackageAppController,
        shizuku: ShizukuAppController,
    ): AppController = ResolvingAppController(packageManager, shizuku, A11yBridge.connected)

    @Provides
    @Singleton
    fun capabilityGraph(
        a11yReader: A11yScreenReader,
        a11yInput: A11yInputController,
        a11yCapturer: A11yScreenCapturer,
        packageManager: PackageAppController,
        shizukuReader: ShizukuScreenReader,
        shizukuInput: ShizukuInputController,
        shizukuCapturer: ShizukuScreenCapturer,
        shizukuApps: ShizukuAppController,
        scope: CoroutineScope,
    ): CapabilityGraph =
        CapabilityGraph(
            mapOf(
                CapabilityId.READ_UI to listOf(a11yReader, shizukuReader),
                CapabilityId.INJECT_INPUT to listOf(shizukuInput, a11yInput),
                CapabilityId.CAPTURE_SCREEN to listOf(shizukuCapturer, a11yCapturer),
                CapabilityId.LAUNCH_APPS to listOf(packageManager, shizukuApps),
            ),
            scope,
        )

    /** The M3 tools: screen reading, input and apps (spec section 5). */
    @Provides
    @ElementsIntoSet
    fun screenUiAndAppTools(
        reader: ScreenReader,
        input: InputController,
        capturer: ScreenCapturer,
        pipeline: ImagePipeline,
        apps: AppController,
    ): Set<ToolHandler> =
        setOf(
            ScreenSnapshotTool(reader),
            ScreenFindTool(reader),
            ScreenWaitForTool(reader),
            ScreenWaitForChangeTool(reader),
            ScreenCaptureTool(capturer, pipeline, reader),
            UiTapTool(input, reader),
            UiLongPressTool(input, reader),
            UiSwipeTool(input, reader),
            UiTypeTextTool(input, reader),
            UiPressKeyTool(input, reader),
            UiGlobalActionTool(input, reader),
            AppListTool(apps),
            AppCurrentTool(reader),
            AppLaunchTool(apps, reader),
            AppOpenUrlTool(apps, reader),
        )

    @Provides
    @Singleton
    fun toolRegistry(
        handlers: Set<@JvmSuppressWildcards ToolHandler>,
        policyProfile: PolicyProfile,
    ): ToolRegistry = ToolRegistry(handlers, policyProfile)

    @Provides
    @Singleton
    fun auditSink(): AuditSink = InMemoryAuditSink()

    @Provides
    @Singleton
    fun authorizationServer(
        @ApplicationContext context: Context,
    ): AuthorizationServer = AuthorizationServer(FileOAuthStore(File(context.noBackupFilesDir, "oauth.json")))

    @Provides
    @Singleton
    fun approvalCenter(
        @ApplicationContext context: Context,
        oauth: AuthorizationServer,
        scope: CoroutineScope,
    ): ApprovalCenter = ApprovalCenter(context, oauth, scope)

    @Provides
    @Singleton
    fun policyEngine(
        @ApplicationContext context: Context,
        reader: ScreenReader,
        approvals: ApprovalCenter,
    ): PolicyEngine =
        PolicyEngine(
            appPolicy = AppPolicy(ownPackages = setOf(context.packageName)),
            foreground = { runCatching { reader.foreground()?.packageName }.getOrNull() },
            confirmer = approvals,
        )

    @Provides
    @Singleton
    fun callDispatcher(
        registry: ToolRegistry,
        auditSink: AuditSink,
        policy: PolicyEngine,
    ): CallDispatcher = CallDispatcher(registry, auditSink, policy)

    @Provides
    @Singleton
    fun tailscaleProvider(
        @ApplicationContext context: Context,
        scope: CoroutineScope,
    ): TailscaleProvider = TailscaleProvider(context, scope, McpHttpServer.DEFAULT_REMOTE_PORT)

    @Provides
    @Singleton
    fun secretStore(
        @ApplicationContext context: Context,
    ): SecretStore = KeystoreSecretStore(context.noBackupFilesDir)

    @Provides
    @Singleton
    fun certificateManager(
        @ApplicationContext context: Context,
        scope: CoroutineScope,
        secrets: SecretStore,
    ): CertificateManager = CertificateManager(context, scope, secrets)

    @Provides
    @Singleton
    fun wireGuardProvider(
        @ApplicationContext context: Context,
        scope: CoroutineScope,
        secrets: SecretStore,
        certificates: CertificateManager,
    ): WireGuardProvider = WireGuardProvider(context, scope, secrets, certificates, McpHttpServer.DEFAULT_REMOTE_PORT)

    @Provides
    @Singleton
    fun zeroTierProvider(
        @ApplicationContext context: Context,
        scope: CoroutineScope,
        certificates: CertificateManager,
    ): ZeroTierProvider = ZeroTierProvider(context, scope, certificates, McpHttpServer.DEFAULT_REMOTE_PORT)

    @Provides
    @Singleton
    fun localTokenStore(
        @ApplicationContext context: Context,
    ): LocalTokenStore = LocalTokenStore(context.noBackupFilesDir)

    @Provides
    @Singleton
    fun mcpHttpServer(
        registry: ToolRegistry,
        dispatcher: CallDispatcher,
        tokens: LocalTokenStore,
        oauth: AuthorizationServer,
        tailscale: TailscaleProvider,
        wireGuard: WireGuardProvider,
        zeroTier: ZeroTierProvider,
    ): McpHttpServer =
        McpHttpServer(
            factory = McpServerFactory(registry, dispatcher, appVersion = BuildConfig.VERSION_NAME),
            sessions = SessionFactory(),
            localToken = tokens::current,
            oauth = oauth,
            remoteHosts = {
                listOf(tailscale, wireGuard, zeroTier)
                    .mapNotNull { (it.state.value as? NetworkState.Connected)?.hostname }
                    .toSet()
            },
        )
}
