package app.pocketpilot.agent.runtime

import app.pocketpilot.agent.providers.api.ModelProvider
import app.pocketpilot.core.model.Scope
import kotlinx.serialization.Serializable

/** Limits for one run (spec section 9, Budgets). A run stops when it reaches any of them. */
@Serializable
data class AgentBudgets(
    val maxSteps: Int = 40,
    /** Time the run spends working; time paused for a takeover does not count. */
    val maxWallTimeMs: Long = 10 * 60_000L,
    /** In US dollars; null means no limit, the default for local models. */
    val maxCostUsd: Double? = null,
    val maxImagesPerRequest: Int = 2,
)

/** How the model calls tools. */
enum class ToolMode {
    /** The provider's own tool calling. */
    NATIVE,

    /** The model writes `{"action": ..., "args": ...}` in its reply, for models without tool calling. */
    JSON_FALLBACK,
}

/** The model a run uses and what it can do, from the model registry. */
class RunModel(
    val provider: ModelProvider,
    val model: String,
    val toolMode: ToolMode = ToolMode.NATIVE,
    val vision: Boolean = false,
    /** Estimated cost in US dollars for the given token counts, or null when the price is unknown. */
    val estimateCost: (inputTokens: Long, outputTokens: Long) -> Double? = { _, _ -> null },
    val maxOutputTokens: Int = 4096,
)

/** What the owner asked for and which scopes the run may use. */
data class AgentTask(
    val goal: String,
    val scopes: Set<Scope>,
)

@Serializable
enum class RunStatus {
    RUNNING,

    /** The owner took over; the run waits for [AgentRun.resume]. */
    PAUSED,

    /** The model said it was done. */
    FINISHED,

    /** The owner pressed Stop. */
    STOPPED,

    /** A budget ran out. */
    OUT_OF_BUDGET,

    /** The model or the tools failed too often, or the provider refused the request. */
    FAILED,
}

/** One tool call in a step, as stored in the transcript. */
@Serializable
data class StepCall(
    val tool: String,
    val argsJson: String,
    /** The first part of the tool's text result. */
    val resultSummary: String,
    val isError: Boolean,
)

/** One plan-act-observe step (spec section 9, Transcript). */
@Serializable
data class AgentStep(
    val index: Int,
    val atMillis: Long,
    val text: String? = null,
    val calls: List<StepCall> = emptyList(),
    /** dHash of the screen when the tools returned an image, as hex. */
    val screenHash: String? = null,
    /** A note from the runtime, such as a retry or the owner resuming. */
    val note: String? = null,
)

/** A run's live state, also what the transcript stores. */
@Serializable
data class RunState(
    val id: String,
    val goal: String,
    val provider: String,
    val model: String,
    val startedAtMillis: Long,
    val status: RunStatus = RunStatus.RUNNING,
    val steps: List<AgentStep> = emptyList(),
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    /** Estimated cost in US dollars, when the model's price is known. */
    val costUsd: Double? = null,
    /** The model's closing summary, or why the run ended otherwise. */
    val outcome: String? = null,
    val endedAtMillis: Long? = null,
) {
    val active: Boolean get() = status == RunStatus.RUNNING || status == RunStatus.PAUSED
}

/** Keeps run transcripts; the app stores them on disk. */
interface RunStore {
    suspend fun save(run: RunState)
}
