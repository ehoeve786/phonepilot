package app.pocketpilot.agent.runtime

import app.pocketpilot.agent.providers.api.Message
import app.pocketpilot.agent.providers.api.ModelEvent
import app.pocketpilot.agent.providers.api.ModelRequest
import app.pocketpilot.agent.providers.api.Part
import app.pocketpilot.agent.providers.api.Role
import app.pocketpilot.agent.providers.api.StopReason
import app.pocketpilot.agent.providers.api.ToolChoice
import app.pocketpilot.core.common.Clock
import app.pocketpilot.core.model.ContentPart
import app.pocketpilot.core.model.Session
import app.pocketpilot.core.model.SessionId
import app.pocketpilot.core.model.ToolErrorCode
import app.pocketpilot.core.model.ToolResult
import app.pocketpilot.core.orchestrator.CallDispatcher
import app.pocketpilot.core.orchestrator.SessionFactory
import app.pocketpilot.core.orchestrator.ToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.util.Base64

/**
 * Agent mode (spec section 9): runs one task at a time as an `AGENT_RUN` session, calling the same
 * tools through the same [CallDispatcher] as remote clients, so every action passes scopes, policy,
 * confirmations and audit.
 */
class AgentRunner(
    private val registry: ToolRegistry,
    private val dispatcher: CallDispatcher,
    private val sessions: SessionFactory,
    private val store: RunStore? = null,
    private val clock: Clock = Clock.System,
    /** Called when a run's session ends, so the policy engine can forget its confirmations. */
    private val onSessionEnd: (SessionId) -> Unit = {},
    /** Called before a run starts, for example to pre-approve tools the owner allows in their own runs. */
    private val onSessionStart: (Session) -> Unit = {},
) {
    private val mutableCurrent = MutableStateFlow<AgentRun?>(null)

    /** The latest run, active or finished. */
    val current: StateFlow<AgentRun?> = mutableCurrent.asStateFlow()

    /** Starts [task] in [scope]. Only one run can be active; starting another while one is fails. */
    @Synchronized
    fun start(
        task: AgentTask,
        model: RunModel,
        budgets: AgentBudgets,
        scope: CoroutineScope,
    ): AgentRun {
        check(
            mutableCurrent.value
                ?.state
                ?.value
                ?.active != true,
        ) { "A run is already in progress" }
        val session = sessions.agentSession(task.scopes)
        onSessionStart(session)
        val run = AgentRun(session, task, model, budgets, registry, dispatcher, store, clock, onSessionEnd)
        mutableCurrent.value = run
        run.launchIn(scope)
        return run
    }
}

/** One run: its live [state] and the owner's controls. */
class AgentRun internal constructor(
    private val session: Session,
    private val task: AgentTask,
    private val model: RunModel,
    private val budgets: AgentBudgets,
    registry: ToolRegistry,
    private val dispatcher: CallDispatcher,
    private val store: RunStore?,
    private val clock: Clock,
    private val onSessionEnd: (SessionId) -> Unit,
) {
    val id: String get() = session.id.value

    private val mutableState =
        MutableStateFlow(
            RunState(
                id = session.id.value,
                goal = task.goal,
                provider = model.provider.id,
                model = model.model,
                startedAtMillis = clock.nowMillis(),
            ),
        )
    val state: StateFlow<RunState> = mutableState.asStateFlow()

    private val tools =
        AgentTools(
            registry.specs.filter { spec ->
                task.scopes.containsAll(spec.requiredScopes) && (model.vision || spec.name != SCREEN_CAPTURE)
            },
        )
    private val messages = mutableListOf<Message>()
    private val paused = MutableStateFlow(false)
    private var job: Job? = null
    private var activeMs = 0L
    private var segmentStart = 0L
    private var consecutiveErrors = 0

    /** The owner takes over: the run stops before its next action and waits for [resume]. */
    fun pause() {
        if (state.value.status == RunStatus.RUNNING) paused.value = true
    }

    /** Hands control back; the run reads the screen again before going on. */
    fun resume() {
        paused.value = false
    }

    fun stop() {
        job?.cancel()
    }

    @OptIn(DelicateCoroutinesApi::class)
    internal fun launchIn(scope: CoroutineScope) {
        // ATOMIC: a run stopped before it gets going still reaches its catch block and ends as STOPPED.
        job =
            scope.launch(start = CoroutineStart.ATOMIC) {
                try {
                    run()
                } catch (e: CancellationException) {
                    withContext(NonCancellable) { end(RunStatus.STOPPED, "Stopped by the owner") }
                    throw e
                } catch (e: Exception) {
                    end(RunStatus.FAILED, "The run failed: ${e.message ?: e::class.simpleName}")
                } finally {
                    onSessionEnd(session.id)
                }
            }
    }

    private suspend fun run() {
        segmentStart = clock.nowMillis()
        messages += Message(Role.USER, firstParts(task.goal))
        while (true) {
            if (paused.value) awaitResume()
            outOfBudget()?.let { return end(RunStatus.OUT_OF_BUDGET, it) }

            val reply =
                withTimeoutOrNull(remainingMs()) { ask() }
                    ?: return end(RunStatus.OUT_OF_BUDGET, "Reached the time limit")
            if (reply is Reply.Failed) return end(RunStatus.FAILED, reply.message)
            reply as Reply.Answer
            // The owner took over while the model was thinking: its plan is for a screen that may be
            // gone, so drop it and plan again after the takeover.
            if (paused.value) continue

            val done =
                when (model.toolMode) {
                    ToolMode.NATIVE -> nativeStep(reply)
                    ToolMode.JSON_FALLBACK -> jsonStep(reply)
                }
            if (done != null) return end(RunStatus.FINISHED, done)
            if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                return end(RunStatus.FAILED, "Stopped after $MAX_CONSECUTIVE_ERRORS steps in a row failed")
            }
        }
    }

    private suspend fun firstParts(goal: String): List<Part> {
        val info = if (tools.has(DEVICE_INFO)) text(call(DEVICE_INFO)) else null
        val snapshot = if (tools.has(SCREEN_SNAPSHOT)) text(call(SCREEN_SNAPSHOT)) else null
        return listOf(Part.Text(Prompts.firstMessage(goal, info, snapshot))) + screenshot()
    }

    /** A screenshot for models with vision, as message parts. */
    private suspend fun screenshot(): List<Part> =
        if (model.vision && tools.has(SCREEN_CAPTURE)) {
            call(SCREEN_CAPTURE).content.filterIsInstance<ContentPart.Image>().map(::image)
        } else {
            emptyList()
        }

    /** Returns the closing summary when the model is done, or null to go on. */
    private suspend fun nativeStep(reply: Reply.Answer): String? {
        if (reply.calls.isEmpty()) return reply.text.ifBlank { "Done" }
        messages +=
            Message(
                Role.ASSISTANT,
                listOfNotNull(reply.text.takeIf(String::isNotBlank)?.let(Part::Text)) +
                    reply.calls.map { Part.ToolCall(it.id, it.name, it.argsJson) },
            )
        val results = mutableListOf<Part>()
        val calls = mutableListOf<StepCall>()
        var hash: String? = null
        for (c in reply.calls) {
            if (paused.value) break
            val (result, record) = execute(c.name, c.argsJson)
            results += Part.ToolResult(c.id, c.name, convert(result), result.isError)
            calls += record
            hash = screenHash(result) ?: hash
        }
        // Every call needs a result, including ones skipped because the owner took over.
        for (c in reply.calls.drop(calls.size)) {
            results += Part.ToolResult(c.id, c.name, listOf(Part.Text("Not run: the owner took over.")), isError = true)
        }
        messages += Message(Role.USER, results)
        recordStep(reply.text, calls, hash)
        return null
    }

    private suspend fun jsonStep(first: Reply.Answer): String? {
        var reply = first
        var parsed = JsonFallback.parse(reply.text)
        var retries = 0
        while (parsed is JsonFallback.Parsed.Invalid && retries < JSON_RETRIES) {
            retries++
            messages += Message(Role.ASSISTANT, listOf(Part.Text(reply.text)))
            messages += Message.user(JsonFallback.retryMessage(parsed.problem))
            reply = (withTimeoutOrNull(remainingMs()) { ask() } as? Reply.Answer) ?: break
            parsed = JsonFallback.parse(reply.text)
        }
        return when (parsed) {
            is JsonFallback.Parsed.Done -> {
                parsed.summary.ifBlank { "Done" }
            }

            is JsonFallback.Parsed.Invalid -> {
                messages += Message(Role.ASSISTANT, listOf(Part.Text(reply.text)))
                messages += Message.user(JsonFallback.retryMessage(parsed.problem))
                recordStep(reply.text, emptyList(), null, note = "The model's reply was not a valid action: ${parsed.problem}")
                consecutiveErrors++
                null
            }

            is JsonFallback.Parsed.Call -> {
                messages += Message(Role.ASSISTANT, listOf(Part.Text(reply.text)))
                val (result, record) = execute(parsed.action, parsed.args.toString())
                messages +=
                    Message(Role.USER, listOf(Part.Text("${TextBudget.JSON_RESULT}${parsed.action}:")) + convert(result))
                recordStep(reply.text, listOf(record), screenHash(result))
                null
            }
        }
    }

    private suspend fun execute(
        name: String,
        argsJson: String,
    ): Pair<ToolResult, StepCall> {
        val spec = tools.resolve(name)
        val args =
            try {
                Json.parseToJsonElement(argsJson.ifBlank { "{}" }).jsonObject
            } catch (e: IllegalArgumentException) {
                null
            }
        val result =
            when {
                spec == null -> {
                    ToolResult.error(ToolErrorCode.UNKNOWN_TOOL, "No tool named '$name'")
                }

                args == null -> {
                    ToolResult.error(ToolErrorCode.INVALID_ARGUMENTS, "The arguments were not a JSON object")
                }

                else -> {
                    dispatcher.dispatch(session, spec.name, args)
                }
            }
        val summary = text(result).take(SUMMARY_CHARS)
        return result to StepCall(spec?.name ?: name, argsJson, summary, result.isError)
    }

    private suspend fun call(
        tool: String,
        args: JsonObject = JsonObject(emptyMap()),
    ): ToolResult = dispatcher.dispatch(session, tool, args)

    /** Asks the model, retrying provider errors that may pass. */
    private suspend fun ask(): Reply {
        var attempt = 0
        while (true) {
            val request =
                ModelRequest(
                    model = model.model,
                    system = Prompts.system(model.toolMode, tools.defs),
                    messages = ImageBudget.apply(TextBudget.apply(messages), if (model.vision) budgets.maxImagesPerRequest else 0),
                    tools = if (model.toolMode == ToolMode.NATIVE) tools.defs else emptyList(),
                    maxTokens = model.maxOutputTokens,
                    toolChoice = if (model.toolMode == ToolMode.NATIVE) ToolChoice.AUTO else null,
                )
            val text = StringBuilder()
            val calls = mutableListOf<ModelEvent.ToolCall>()
            var error: ModelEvent.Error? = null
            var stop = StopReason.OTHER
            model.provider.stream(request).collect { event ->
                when (event) {
                    is ModelEvent.TextDelta -> text.append(event.text)
                    is ModelEvent.ToolCall -> calls += event
                    is ModelEvent.Usage -> addUsage(event)
                    is ModelEvent.Stop -> stop = event.reason
                    is ModelEvent.Error -> error = event
                }
            }
            val failure = error ?: return Reply.Answer(text.toString(), calls, stop)
            if (!failure.retryable || attempt >= MODEL_RETRIES) {
                return Reply.Failed("${model.provider.displayName} error (${failure.code}): ${failure.message}")
            }
            attempt++
            note("Model call failed (${failure.code}), retrying")
            delay((failure.retryAfterMs ?: (RETRY_BASE_MS * attempt)).coerceAtMost(MAX_RETRY_WAIT_MS))
        }
    }

    private fun addUsage(usage: ModelEvent.Usage) {
        mutableState.update { s ->
            val input = s.inputTokens + usage.inputTokens
            val output = s.outputTokens + usage.outputTokens
            s.copy(inputTokens = input, outputTokens = output, costUsd = model.estimateCost(input, output))
        }
    }

    private suspend fun awaitResume() {
        activeMs += clock.nowMillis() - segmentStart
        setStatus(RunStatus.PAUSED)
        paused.first { !it }
        segmentStart = clock.nowMillis()
        setStatus(RunStatus.RUNNING)
        val snapshot = if (tools.has(SCREEN_SNAPSHOT)) text(call(SCREEN_SNAPSHOT)) else null
        val parts = listOf(Part.Text(Prompts.resumed(snapshot))) + screenshot()
        // Tool results travel in a user message too; add to it rather than send two user turns.
        val last = messages.lastOrNull()
        if (last?.role == Role.USER) {
            messages[messages.lastIndex] = last.copy(parts = last.parts + parts)
        } else {
            messages += Message(Role.USER, parts)
        }
        note("The owner resumed the run")
    }

    private fun outOfBudget(): String? {
        val s = state.value
        val cost = s.costUsd
        val maxCost = budgets.maxCostUsd
        return when {
            s.steps.count {
                it.calls.isNotEmpty() || it.text != null
            } >= budgets.maxSteps -> "Reached the limit of ${budgets.maxSteps} steps"

            maxCost != null && cost != null && cost >= maxCost -> "Reached the cost limit of $%.2f".format(maxCost)

            remainingMs() <= 0 -> "Reached the time limit"

            else -> null
        }
    }

    private fun remainingMs(): Long = budgets.maxWallTimeMs - activeMs - (clock.nowMillis() - segmentStart)

    private suspend fun recordStep(
        text: String,
        calls: List<StepCall>,
        hash: String?,
        note: String? = null,
    ) {
        consecutiveErrors =
            when {
                note != null -> consecutiveErrors
                calls.isNotEmpty() && calls.all(StepCall::isError) -> consecutiveErrors + 1
                else -> 0
            }
        mutableState.update { s ->
            s.copy(
                steps =
                    s.steps +
                        AgentStep(
                            index = s.steps.size,
                            atMillis = clock.nowMillis(),
                            text = text.takeIf(String::isNotBlank),
                            calls = calls,
                            screenHash = hash,
                            note = note,
                        ),
            )
        }
        store?.save(state.value)
    }

    private fun note(text: String) {
        mutableState.update { s -> s.copy(steps = s.steps + AgentStep(index = s.steps.size, atMillis = clock.nowMillis(), note = text)) }
    }

    private fun setStatus(status: RunStatus) {
        mutableState.update { it.copy(status = status) }
    }

    private suspend fun end(
        status: RunStatus,
        outcome: String,
    ) {
        if (!state.value.active) return
        mutableState.update { it.copy(status = status, outcome = outcome, endedAtMillis = clock.nowMillis()) }
        store?.save(state.value)
    }

    private sealed interface Reply {
        data class Answer(
            val text: String,
            val calls: List<ModelEvent.ToolCall>,
            val stop: StopReason,
        ) : Reply

        data class Failed(
            val message: String,
        ) : Reply
    }

    private companion object {
        const val DEVICE_INFO = "device.info"
        const val SCREEN_SNAPSHOT = "screen.snapshot"
        const val SCREEN_CAPTURE = "screen.capture"
        const val MAX_CONSECUTIVE_ERRORS = 3
        const val JSON_RETRIES = 2
        const val MODEL_RETRIES = 2
        const val RETRY_BASE_MS = 2_000L
        const val MAX_RETRY_WAIT_MS = 30_000L
        const val SUMMARY_CHARS = 300
        val HASH = Regex("\"hash\"\\s*:\\s*\"([0-9a-f]{16})\"")

        fun text(result: ToolResult): String =
            buildString {
                if (result.isError) append("Error ${result.errorCode ?: ""}: ")
                append(result.content.filterIsInstance<ContentPart.Text>().joinToString("\n") { it.text })
                result.recoveryHint?.let { append("\nHint: ").append(it) }
            }

        fun convert(result: ToolResult): List<Part> =
            buildList {
                add(Part.Text(text(result)))
                result.content.filterIsInstance<ContentPart.Image>().forEach { add(image(it)) }
            }

        fun image(part: ContentPart.Image): Part = Part.Image(Base64.getDecoder().decode(part.base64), part.mimeType)

        fun screenHash(result: ToolResult): String? =
            result.content.filterIsInstance<ContentPart.Text>().firstNotNullOfOrNull { HASH.find(it.text)?.groupValues?.get(1) }
    }
}
