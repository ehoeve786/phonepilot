package app.pocketpilot.agent

import app.pocketpilot.agent.providers.anthropic.AnthropicProvider
import app.pocketpilot.agent.providers.api.ModelProvider
import app.pocketpilot.agent.providers.gemini.GeminiProvider
import app.pocketpilot.agent.providers.openai.OpenAiProvider
import app.pocketpilot.agent.providers.openaicompatible.CompatibleProfile
import app.pocketpilot.agent.providers.openaicompatible.compatibleProvider
import app.pocketpilot.agent.registry.ModelRegistry
import app.pocketpilot.agent.registry.ToolSupport
import app.pocketpilot.agent.registry.estimateCost
import app.pocketpilot.agent.runtime.AgentProfile
import app.pocketpilot.agent.runtime.AgentRunner
import app.pocketpilot.agent.runtime.AgentTask
import app.pocketpilot.agent.runtime.FileRunStore
import app.pocketpilot.agent.runtime.ProfileStore
import app.pocketpilot.agent.runtime.ProviderKind
import app.pocketpilot.agent.runtime.RunModel
import app.pocketpilot.agent.runtime.RunState
import app.pocketpilot.agent.runtime.ToolMode
import app.pocketpilot.capability.api.screen.ScreenReader
import app.pocketpilot.core.model.Scope
import app.pocketpilot.feature.agent.ModelList
import app.pocketpilot.feature.agent.ModelOption
import app.pocketpilot.network.api.SecretStore
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Agent mode in the app: turns the owner's [AgentProfile]s into providers, starts runs on the
 * process scope so they outlive the screen, and keeps the transcripts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentCoordinator(
    private val http: HttpClient,
    private val registry: ModelRegistry,
    private val runner: AgentRunner,
    val profiles: ProfileStore,
    private val runs: FileRunStore,
    private val secrets: SecretStore,
    private val reader: ScreenReader,
    private val ownPackage: String,
    private val scope: CoroutineScope,
    /** Shows the live run outside the app, so the owner can take over or stop it from anywhere. */
    private val onRunState: (RunState?) -> Unit = {},
) {
    val current: StateFlow<RunState?> =
        runner.current
            .flatMapLatest { it?.state ?: flowOf(null) }
            .stateIn(scope, SharingStarted.Eagerly, null)

    private val mutableHistory = MutableStateFlow<List<RunState>>(emptyList())
    val history: StateFlow<List<RunState>> = mutableHistory.asStateFlow()

    init {
        scope.launch {
            refreshHistory()
            // A run's transcript lands in the history when it ends.
            current.collect {
                onRunState(it)
                if (it != null && !it.active) refreshHistory()
            }
        }
    }

    /**
     * Starts [goal] with the profile [profileId] once PocketPilot itself has left the screen: the
     * policy never lets anything act on PocketPilot's own UI, so the caller sends its task to the back.
     */
    fun start(
        goal: String,
        profileId: String,
        /** The owner ticked "Allow settings changes": settings.set runs without a prompt in this run. */
        allowSettings: Boolean,
    ) {
        val profile = profiles.profiles.value.firstOrNull { it.id == profileId } ?: return
        if (current.value?.active == true) return
        scope.launch {
            withTimeoutOrNull(LEAVE_TIMEOUT_MS) {
                while (runCatching { reader.foreground()?.packageName }.getOrNull() == ownPackage) delay(LEAVE_POLL_MS)
            }
            try {
                runner.start(
                    AgentTask(goal, AGENT_SCOPES, preApproved = if (allowSettings) setOf(SETTINGS_SET) else emptySet()),
                    runModel(profile),
                    profile.budgets(),
                    scope,
                )
            } catch (e: IllegalStateException) {
                // Another run started in the meantime.
            } catch (e: IllegalArgumentException) {
                // The profile's server address is not a URL; the profile form does not allow saving one.
            }
        }
    }

    fun pause() = runner.current.value?.pause()

    fun resume() = runner.current.value?.resume()

    fun stop() = runner.current.value?.stop()

    fun saveProfile(
        profile: AgentProfile,
        apiKey: String?,
    ) {
        apiKey?.let { secrets.put(profile.keyName, it) }
        profiles.save(profile)
    }

    fun deleteProfile(id: String) {
        profiles.profiles.value
            .firstOrNull { it.id == id }
            ?.let { secrets.remove(it.keyName) }
        profiles.remove(id)
    }

    fun hasKey(profile: AgentProfile): Boolean = !secrets.get(profile.keyName).isNullOrBlank()

    /** Lists the models the profile reaches, for the model picker; also checks the key or the server address. */
    fun listModels(
        profile: AgentProfile,
        apiKey: String?,
        onResult: (ModelList) -> Unit,
    ) {
        scope.launch {
            val result =
                try {
                    val models = provider(profile, apiKey ?: secrets.get(profile.keyName)).listModels()
                    ModelList.Loaded(models.map { ModelOption(it.id, it.displayName) }.sortedBy { it.label.lowercase() })
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ModelList.Failed(problem(e))
                }
            withContext(Dispatchers.Main) { onResult(result) }
        }
    }

    /** A provider failure in words the owner can act on. */
    private fun problem(e: Exception): String {
        val message = e.message.orEmpty()
        return when {
            e is java.net.UnknownHostException || e is java.net.ConnectException ||
                e is java.net.NoRouteToHostException || e is java.net.SocketTimeoutException -> {
                "the server did not answer. Check the address, and that the server listens on the network."
            }

            "API_KEY_INVALID" in message || message.startsWith("HTTP 401") || message.startsWith("HTTP 403") -> {
                "the API key was not accepted."
            }

            else -> {
                message.take(MAX_PROBLEM_CHARS).ifBlank { e::class.simpleName.orEmpty() }
            }
        }
    }

    private fun runModel(profile: AgentProfile): RunModel {
        val descriptor = registry.describe(profile.providerId, profile.model)
        return RunModel(
            provider = provider(profile, secrets.get(profile.keyName)),
            model = profile.model,
            toolMode = if (descriptor.tools == ToolSupport.NATIVE) ToolMode.NATIVE else ToolMode.JSON_FALLBACK,
            vision = descriptor.vision,
            estimateCost = { input, output -> estimateCost(descriptor, input.toInt(), output.toInt()) },
            maxOutputTokens = (descriptor.maxOutputTokens ?: MAX_OUTPUT_TOKENS).coerceAtMost(MAX_OUTPUT_TOKENS),
        )
    }

    private fun provider(
        profile: AgentProfile,
        key: String?,
    ): ModelProvider =
        when (profile.kind) {
            ProviderKind.ANTHROPIC -> {
                AnthropicProvider(http, key.orEmpty())
            }

            ProviderKind.OPENAI -> {
                OpenAiProvider(http, key)
            }

            ProviderKind.GEMINI -> {
                GeminiProvider(http, key.orEmpty())
            }

            ProviderKind.OPENAI_COMPATIBLE -> {
                compatibleProvider(
                    http,
                    CompatibleProfile(
                        name = profile.compatibleName(),
                        baseUrl = profile.baseUrl.orEmpty(),
                        apiKey = key?.ifBlank { null },
                        displayName = profile.name,
                    ),
                )
            }
        }

    private suspend fun refreshHistory() {
        mutableHistory.value = withContext(Dispatchers.IO) { runs.list() }
    }

    private companion object {
        /** Everything the local token gets: raw shell and Termux stay behind their own toggles. */
        val AGENT_SCOPES = Scope.KNOWN - setOf(Scope.SHELL_EXEC, Scope.TERMUX_RUN)
        const val SETTINGS_SET = "settings.set"
        const val LEAVE_TIMEOUT_MS = 3_000L
        const val LEAVE_POLL_MS = 150L
        const val MAX_PROBLEM_CHARS = 200
        const val MAX_OUTPUT_TOKENS = 4096
    }
}
