package app.pocketpilot.di

import android.content.Context
import app.pocketpilot.agent.AgentCoordinator
import app.pocketpilot.agent.AgentNotifier
import app.pocketpilot.agent.registry.ModelRegistry
import app.pocketpilot.agent.runtime.AgentRunner
import app.pocketpilot.agent.runtime.FileRunStore
import app.pocketpilot.agent.runtime.ProfileStore
import app.pocketpilot.capability.api.screen.ScreenReader
import app.pocketpilot.core.model.Scope
import app.pocketpilot.core.orchestrator.CallDispatcher
import app.pocketpilot.core.orchestrator.SessionFactory
import app.pocketpilot.core.orchestrator.ToolRegistry
import app.pocketpilot.core.policy.PolicyEngine
import app.pocketpilot.network.api.SecretStore
import app.pocketpilot.security.ShellSwitch
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CoroutineScope
import java.io.File
import javax.inject.Singleton

/** Agent mode (spec section 9): model providers, the run loop and its stores. */
@Module
@InstallIn(SingletonComponent::class)
object AgentModule {
    /** Shared by every provider; model replies can take a minute on a slow local server. */
    @Provides
    @Singleton
    fun httpClient(): HttpClient =
        HttpClient(OkHttp) {
            install(HttpTimeout) {
                connectTimeoutMillis = 15_000
                requestTimeoutMillis = 180_000
                socketTimeoutMillis = 180_000
            }
        }

    @Provides
    @Singleton
    fun modelRegistry(): ModelRegistry = ModelRegistry.loadBundled()

    @Provides
    @Singleton
    fun runStore(
        @ApplicationContext context: Context,
    ): FileRunStore = FileRunStore(File(context.noBackupFilesDir, "agent-runs"))

    @Provides
    @Singleton
    fun agentRunner(
        registry: ToolRegistry,
        dispatcher: CallDispatcher,
        store: FileRunStore,
        policy: PolicyEngine,
    ): AgentRunner =
        AgentRunner(
            registry,
            dispatcher,
            SessionFactory(),
            store,
            onSessionEnd = policy::endSession,
            preConfirm = policy::preConfirm,
        )

    @Provides
    @Singleton
    fun agentCoordinator(
        @ApplicationContext context: Context,
        http: HttpClient,
        models: ModelRegistry,
        runner: AgentRunner,
        runs: FileRunStore,
        secrets: SecretStore,
        reader: ScreenReader,
        shell: ShellSwitch,
        scope: CoroutineScope,
    ): AgentCoordinator {
        val notifier = AgentNotifier(context)
        return AgentCoordinator(
            http = http,
            registry = models,
            runner = runner,
            profiles = ProfileStore(File(context.noBackupFilesDir, "agent-profiles.json")),
            runs = runs,
            secrets = secrets,
            reader = reader,
            ownPackage = context.packageName,
            scope = scope,
            onRunState = notifier::update,
            extraScopes = { if (shell.enabled.value) setOf(Scope.SHELL_EXEC) else emptySet() },
        )
    }
}
