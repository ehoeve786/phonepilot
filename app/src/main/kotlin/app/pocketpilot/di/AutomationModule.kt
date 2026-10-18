package app.pocketpilot.di

import android.content.Context
import app.pocketpilot.agent.AgentCoordinator
import app.pocketpilot.automation.AndroidAutomationHost
import app.pocketpilot.automation.AutomationNotifier
import app.pocketpilot.automation.AutomationRuntime
import app.pocketpilot.automation.Calendars
import app.pocketpilot.automation.HomeAssistant
import app.pocketpilot.automation.PhoneState
import app.pocketpilot.automation.PlaceWatcher
import app.pocketpilot.automation.TriggerSources
import app.pocketpilot.capability.api.notifications.NotificationPoster
import app.pocketpilot.capability.api.screen.ScreenCapturer
import app.pocketpilot.capability.api.screen.ScreenReader
import app.pocketpilot.capability.files.SafFileStore
import app.pocketpilot.capability.shizuku.ShizukuConnection
import app.pocketpilot.capability.shizuku.ShizukuShellRunner
import app.pocketpilot.capability.shizuku.ShizukuSystemReader
import app.pocketpilot.core.automation.AutomationCatalogTool
import app.pocketpilot.core.automation.AutomationListTool
import app.pocketpilot.core.automation.AutomationProposeTool
import app.pocketpilot.core.automation.AutomationRunTool
import app.pocketpilot.core.automation.FilePersistence
import app.pocketpilot.core.automation.RuleStore
import app.pocketpilot.core.model.PolicyProfile
import app.pocketpilot.core.orchestrator.CallDispatcher
import app.pocketpilot.core.orchestrator.ToolHandler
import app.pocketpilot.core.policy.AppMode
import app.pocketpilot.core.policy.AppPolicy
import app.pocketpilot.core.policy.PolicyEngine
import app.pocketpilot.core.tools.NotifyPostTool
import app.pocketpilot.network.api.SecretStore
import app.pocketpilot.security.ShellSwitch
import app.pocketpilot.server.http.McpHttpServer
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.ElementsIntoSet
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import java.io.File
import javax.inject.Singleton

/** Automations: the rule store, the engine's phone side, and the automation.* and notify.post tools. */
@Module
@InstallIn(SingletonComponent::class)
object AutomationModule {
    @Provides
    @Singleton
    fun ruleStore(
        @ApplicationContext context: Context,
    ): RuleStore = RuleStore(FilePersistence(File(context.noBackupFilesDir, "automations.json")))

    @Provides
    @Singleton
    fun automationNotifier(
        @ApplicationContext context: Context,
    ): AutomationNotifier = AutomationNotifier(context)

    @Provides
    fun notificationPoster(notifier: AutomationNotifier): NotificationPoster = notifier

    @Provides
    @Singleton
    fun homeAssistant(
        store: RuleStore,
        secrets: SecretStore,
        http: HttpClient,
        scope: CoroutineScope,
    ): HomeAssistant = HomeAssistant(store, secrets, http, scope)

    @Provides
    @Singleton
    fun phoneState(
        @ApplicationContext context: Context,
        shizuku: ShizukuConnection,
        shell: ShizukuShellRunner,
        server: Lazy<McpHttpServer>,
    ): PhoneState = PhoneState(context, shizuku, shell) { server.get() }

    @Provides
    @Singleton
    fun automationRuntime(
        @ApplicationContext context: Context,
        store: RuleStore,
        dispatcher: CallDispatcher,
        policy: PolicyEngine,
        phone: PhoneState,
        shell: ShizukuShellRunner,
        shellSwitch: ShellSwitch,
        appPolicy: AppPolicy,
        agent: Lazy<AgentCoordinator>,
        profile: PolicyProfile,
        notifier: AutomationNotifier,
        scope: CoroutineScope,
        files: SafFileStore,
        homeAssistant: HomeAssistant,
        logs: ShizukuSystemReader,
        screen: ScreenReader,
        capturer: ScreenCapturer,
    ): AutomationRuntime {
        val hidden = { pkg: String -> appPolicy.modeFor(pkg) == AppMode.DENY }
        val calendars = Calendars(context)
        val places = PlaceWatcher(context)
        val host =
            AndroidAutomationHost(
                context = context,
                dispatcher = dispatcher,
                policy = policy,
                phone = phone,
                shell = shell,
                shellOn = { profile.shellExecAvailable && shellSwitch.enabled.value },
                hidden = hidden,
                agent = { agent.get() },
                files = files,
                homeAssistant = homeAssistant,
                calendars = calendars,
                places = places,
                screen = screen,
                capturer = capturer,
                sideload = profile.shellExecAvailable,
            )
        return AutomationRuntime(
            context,
            store,
            host,
            notifier,
            shell,
            scope,
            calendars,
            profile.shellExecAvailable,
        ) { emit ->
            TriggerSources(context, phone, scope, hidden, places, calendars, homeAssistant, logs, profile.shellExecAvailable, emit)
        }
    }

    /**
     * The tools AI apps use to see and suggest automations. A proposal only waits in the app until the
     * owner accepts it; running a rule needs one the owner made for MCP calls.
     */
    @Provides
    @ElementsIntoSet
    fun automationTools(
        store: RuleStore,
        runtime: Lazy<AutomationRuntime>,
        notifier: AutomationNotifier,
        profile: PolicyProfile,
    ): Set<ToolHandler> =
        setOf(
            NotifyPostTool(notifier),
            AutomationCatalogTool(profile.shellExecAvailable),
            AutomationListTool(store),
            AutomationProposeTool(store, profile.shellExecAvailable) { notifier.proposed(it) },
            AutomationRunTool(store) { runtime.get().engine },
        )
}
