package app.pocketpilot.di

import android.content.Context
import app.pocketpilot.BuildConfig
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
import app.pocketpilot.core.audit.AuditSink
import app.pocketpilot.core.audit.InMemoryAuditSink
import app.pocketpilot.core.imaging.ImagePipeline
import app.pocketpilot.core.model.PolicyProfile
import app.pocketpilot.core.orchestrator.CallDispatcher
import app.pocketpilot.core.orchestrator.SessionFactory
import app.pocketpilot.core.orchestrator.ToolHandler
import app.pocketpilot.core.orchestrator.ToolRegistry
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
import app.pocketpilot.server.LocalTokenStore
import app.pocketpilot.server.http.McpHttpServer
import app.pocketpilot.server.mcp.McpServerFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.ElementsIntoSet
import dagger.multibindings.IntoSet
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

    @Provides
    @Singleton
    fun a11yScreenReader(): A11yScreenReader = A11yScreenReader()

    @Provides
    fun screenReader(reader: A11yScreenReader): ScreenReader = reader

    @Provides
    @Singleton
    fun inputController(reader: A11yScreenReader): InputController = A11yInputController(reader)

    @Provides
    @Singleton
    fun screenCapturer(): ScreenCapturer = A11yScreenCapturer()

    @Provides
    @Singleton
    fun imagePipeline(): ImagePipeline = ImagePipeline(BitmapImageEncoder())

    @Provides
    @Singleton
    fun appController(
        @ApplicationContext context: Context,
    ): AppController = PackageAppController(context)

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
    fun callDispatcher(
        registry: ToolRegistry,
        auditSink: AuditSink,
    ): CallDispatcher = CallDispatcher(registry, auditSink)

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
    ): McpHttpServer =
        McpHttpServer(
            factory = McpServerFactory(registry, dispatcher, appVersion = BuildConfig.VERSION_NAME),
            sessions = SessionFactory(),
            localToken = tokens::current,
        )
}
