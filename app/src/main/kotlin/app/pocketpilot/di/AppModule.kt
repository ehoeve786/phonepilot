package app.pocketpilot.di

import android.content.Context
import app.pocketpilot.BuildConfig
import app.pocketpilot.capability.api.DeviceInfoSource
import app.pocketpilot.capability.device.AndroidDeviceInfoSource
import app.pocketpilot.core.audit.AuditSink
import app.pocketpilot.core.audit.InMemoryAuditSink
import app.pocketpilot.core.model.PolicyProfile
import app.pocketpilot.core.orchestrator.CallDispatcher
import app.pocketpilot.core.orchestrator.SessionFactory
import app.pocketpilot.core.orchestrator.ToolHandler
import app.pocketpilot.core.orchestrator.ToolRegistry
import app.pocketpilot.core.tools.DeviceInfoTool
import app.pocketpilot.server.LocalTokenStore
import app.pocketpilot.server.http.McpHttpServer
import app.pocketpilot.server.mcp.McpServerFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
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
