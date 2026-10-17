package app.pocketpilot.agent.runtime

import app.pocketpilot.agent.providers.api.Message
import app.pocketpilot.agent.providers.api.ModelErrorCode
import app.pocketpilot.agent.providers.api.ModelEvent
import app.pocketpilot.agent.providers.api.ModelInfo
import app.pocketpilot.agent.providers.api.ModelProvider
import app.pocketpilot.agent.providers.api.ModelRequest
import app.pocketpilot.agent.providers.api.Part
import app.pocketpilot.agent.providers.api.Role
import app.pocketpilot.agent.providers.api.StopReason
import app.pocketpilot.core.audit.InMemoryAuditSink
import app.pocketpilot.core.common.Clock
import app.pocketpilot.core.model.Availability
import app.pocketpilot.core.model.ContentPart
import app.pocketpilot.core.model.Flavor
import app.pocketpilot.core.model.Phase
import app.pocketpilot.core.model.PolicyProfile
import app.pocketpilot.core.model.RiskTier
import app.pocketpilot.core.model.Scope
import app.pocketpilot.core.model.Session
import app.pocketpilot.core.model.ToolCall
import app.pocketpilot.core.model.ToolErrorCode
import app.pocketpilot.core.model.ToolException
import app.pocketpilot.core.model.ToolResult
import app.pocketpilot.core.model.ToolSpec
import app.pocketpilot.core.orchestrator.CallDispatcher
import app.pocketpilot.core.orchestrator.SessionFactory
import app.pocketpilot.core.orchestrator.ToolHandler
import app.pocketpilot.core.orchestrator.ToolRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AgentRunnerTest {
    private val anyObject = Json.parseToJsonElement("""{"type":"object"}""").jsonObject

    private fun spec(
        name: String,
        scope: Scope = Scope.DEVICE_READ,
    ) = ToolSpec(
        name = name,
        title = name,
        description = "test $name",
        inputSchema = anyObject,
        riskTier = RiskTier.READ,
        requiredScopes = setOf(scope),
        availability = Availability(Phase.P0, Flavor.entries.toSet()),
    )

    private class FakeTool(
        override val spec: ToolSpec,
        val body: suspend (ToolCall) -> ToolResult,
    ) : ToolHandler {
        val calls = mutableListOf<ToolCall>()

        override suspend fun execute(
            call: ToolCall,
            session: Session,
        ): ToolResult {
            calls += call
            return body(call)
        }
    }

    private val png = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))
    private var screen = "Home screen"
    private val info = FakeTool(spec("device.info")) { ToolResult.success("Galaxy S23 Ultra, Android 16") }
    private val snapshot = FakeTool(spec("screen.snapshot")) { ToolResult.success(screen) }
    private val capture =
        FakeTool(spec("screen.capture")) {
            ToolResult(listOf(ContentPart.Text("""{"hash":"0123456789abcdef"}"""), ContentPart.Image(png, "image/png")))
        }
    private val tap = FakeTool(spec("ui.tap", Scope.UI_INTERACT)) { ToolResult.success("tapped") }
    private val broken =
        FakeTool(spec("ui.swipe", Scope.UI_INTERACT)) { throw ToolException(ToolErrorCode.ELEMENT_NOT_FOUND, "nothing there") }
    private val shell = FakeTool(spec("shell.exec", Scope.SHELL_EXEC)) { ToolResult.success("ran") }

    private val scopes = setOf(Scope.DEVICE_READ, Scope.UI_INTERACT)

    /** Replies with each script in turn; a script returns the reply's events for the request it gets. */
    private class ScriptedProvider(
        vararg scripts: suspend (ModelRequest) -> List<ModelEvent>,
    ) : ModelProvider {
        private val queue = ArrayDeque(scripts.toList())
        val requests = mutableListOf<ModelRequest>()
        override val id = "fake"
        override val displayName = "Fake"

        override suspend fun listModels() = listOf(ModelInfo("fake-1"))

        override fun stream(request: ModelRequest): Flow<ModelEvent> =
            flow {
                requests += request
                val script = queue.removeFirstOrNull() ?: error("The model was asked more often than scripted")
                script(request).forEach { emit(it) }
            }
    }

    private fun say(
        text: String,
        stop: StopReason = StopReason.END_TURN,
    ): suspend (ModelRequest) -> List<ModelEvent> = { listOf(ModelEvent.TextDelta(text), ModelEvent.Usage(100, 10), ModelEvent.Stop(stop)) }

    private fun callTool(
        name: String,
        args: String = "{}",
    ): suspend (ModelRequest) -> List<ModelEvent> =
        { listOf(ModelEvent.ToolCall("c-$name", name, args), ModelEvent.Usage(100, 10), ModelEvent.Stop(StopReason.TOOL_USE)) }

    private fun fail(code: ModelErrorCode): suspend (ModelRequest) -> List<ModelEvent> = { listOf(ModelEvent.Error(code, "boom")) }

    private fun TestScope.runner(
        health: ProviderHealth? = null,
        beforeScreenWork: suspend () -> Unit = {},
    ): AgentRunner {
        val clock = Clock { testScheduler.currentTime }
        val registry = ToolRegistry(setOf(info, snapshot, capture, tap, broken, shell), PolicyProfile.OSS)
        return AgentRunner(
            registry,
            CallDispatcher(registry, InMemoryAuditSink(), clock = clock),
            SessionFactory(clock),
            clock = clock,
            beforeScreenWork = beforeScreenWork,
            health = health,
        )
    }

    /** A chat's state once it has answered [replies] messages. */
    private suspend fun AgentRun.answered(replies: Int): RunState =
        state.first { it.status == RunStatus.WAITING && it.steps.count { step -> step.reply != null } >= replies }

    private suspend fun AgentRun.finished(): RunState = state.first { !it.active }

    private suspend fun AgentRun.ended(): RunState = state.first { !it.open }

    @Test
    fun `calls a tool natively, then finishes with the model's summary`() =
        runTest {
            val provider = ScriptedProvider(callTool("ui_tap", """{"x":1,"y":2}"""), say("Opened the app"))
            val run = runner().start(AgentTask("Open the app", scopes), RunModel(provider, "fake-1"), AgentBudgets(), backgroundScope)
            val end = run.finished()

            assertEquals(RunStatus.FINISHED, end.status)
            assertEquals("Opened the app", end.outcome)
            assertEquals(1, tap.calls.size)
            assertEquals(
                "1",
                tap.calls
                    .single()
                    .arguments["x"]
                    .toString(),
            )
            assertEquals(
                "ui.tap",
                end.steps
                    .single()
                    .calls
                    .single()
                    .tool,
            )
            assertEquals(200L, end.inputTokens)

            val first = provider.requests.first()
            assertTrue(first.tools.any { it.name == "ui_tap" })
            assertTrue(first.tools.none { it.name == "shell_exec" }, "tools outside the run's scopes are hidden")
            assertTrue(first.tools.none { it.name == "screen_capture" }, "no screenshots for a model without vision")
            val opening =
                first.messages
                    .single()
                    .parts
                    .filterIsInstance<Part.Text>()
                    .joinToString("\n") { it.text }
            assertTrue("Open the app" in opening && "Galaxy S23 Ultra" in opening && "Home screen" in opening)

            val second = provider.requests[1].messages
            assertEquals(listOf(Role.USER, Role.ASSISTANT, Role.USER), second.map(Message::role))
            val result = second.last().parts.single() as Part.ToolResult
            assertEquals("c-ui_tap", result.id)
        }

    @Test
    fun `a call the provider refuses for an unloaded tool makes the model load it and ask again`() =
        runTest {
            val refused =
                ModelEvent.Error(
                    ModelErrorCode.INVALID_REQUEST,
                    "HTTP 400: attempted to call tool 'shell.exec' which was not in request.tools",
                )
            val provider = ScriptedProvider({ listOf(refused) }, say("It is 10:00"))

            val end =
                runner()
                    .start(
                        AgentTask("What time is it?", scopes),
                        RunModel(provider, "fake-1"),
                        AgentBudgets(),
                        backgroundScope,
                    ).finished()

            assertEquals(RunStatus.FINISHED, end.status)
            assertEquals("It is 10:00", end.outcome)
            assertTrue(end.steps.any { it.note?.startsWith("The model called shell.exec before loading it") == true })
            val retry =
                provider.requests[1]
                    .messages
                    .last()
                    .parts
                    .filterIsInstance<Part.Text>()
                    .single()
                    .text
            assertTrue(retry.startsWith("shell.exec is not loaded yet. Load it first with ${AgentTools.LOAD}"), retry)
        }

    @Test
    fun `moves to the next model when one runs out, skipping models that call tools differently`() =
        runTest {
            val gemini = ScriptedProvider(fail(ModelErrorCode.QUOTA))
            val jsonOnly = ScriptedProvider()
            val openRouter = ScriptedProvider(callTool("ui_tap"), say("Done"))
            val chain =
                RunModel(
                    gemini,
                    "gemini-2.5-flash",
                    label = "Gemini",
                    estimateCost = { _, _ -> 0.0 },
                    fallback =
                        RunModel(
                            jsonOnly,
                            "small",
                            toolMode = ToolMode.JSON_FALLBACK,
                            fallback = RunModel(openRouter, "mistral", label = "OpenRouter", estimateCost = { i, o -> (i + o) / 1000.0 }),
                        ),
                )
            val end = runner().start(AgentTask("Tap", scopes), chain, AgentBudgets(), backgroundScope).finished()

            assertEquals(RunStatus.FINISHED, end.status)
            assertEquals("mistral", end.model)
            assertTrue(end.steps.any { it.note == "Gemini is not available (QUOTA), switching to OpenRouter" })
            assertTrue(jsonOnly.requests.isEmpty())
            assertEquals(2, openRouter.requests.size)
            assertEquals(0.22, end.costUsd!!, 1e-9)
        }

    @Test
    fun `fails over from Gemini to Groq to Mistral on a rate limit, a timeout and an outage`() =
        runTest {
            val health = ProviderHealth(Clock { testScheduler.currentTime })
            val gemini =
                ScriptedProvider({ listOf(ModelEvent.Error(ModelErrorCode.RATE_LIMIT, "HTTP 429: Rate limit", retryAfterMs = 5_000)) })
            val groq = ScriptedProvider(fail(ModelErrorCode.NETWORK))
            val openRouter = ScriptedProvider(fail(ModelErrorCode.SERVER))
            val mistral = ScriptedProvider(say("Done on Mistral"))
            val chain =
                RunModel(
                    gemini,
                    "gemini-2.5-flash",
                    label = "Gemini",
                    fallback =
                        RunModel(
                            groq,
                            "llama-3.3-70b",
                            label = "Groq",
                            fallback =
                                RunModel(
                                    openRouter,
                                    "or",
                                    label = "OpenRouter",
                                    fallback = RunModel(mistral, "mistral-small", label = "Mistral"),
                                ),
                        ),
                )
            val end = runner(health).start(AgentTask("Hello", scopes), chain, AgentBudgets(), backgroundScope).finished()

            assertEquals(RunStatus.FINISHED, end.status)
            assertEquals("Done on Mistral", end.outcome)
            assertEquals(
                listOf(
                    "Gemini is not available (RATE_LIMIT), switching to Groq",
                    "Groq is not available (NETWORK), switching to OpenRouter",
                    "OpenRouter is not available (SERVER), switching to Mistral",
                ),
                end.steps.mapNotNull { it.note },
            )
            val status = health.status.value
            assertEquals(ProviderState.LIMITED, status.getValue("Gemini").state)
            assertEquals(5_000L, status.getValue("Gemini").retryAtMillis)
            assertEquals(ProviderState.DOWN, status.getValue("Groq").state)
            assertEquals(ProviderState.DOWN, status.getValue("OpenRouter").state)
            assertEquals(ProviderState.READY, status.getValue("Mistral").state)
        }

    @Test
    fun `an adapter that throws a timeout fails the turn but not the chat`() =
        runTest {
            val throwing =
                object : ModelProvider {
                    override val id = "gemini"
                    override val displayName = "Gemini"

                    override suspend fun listModels() = emptyList<ModelInfo>()

                    override fun stream(request: ModelRequest): Flow<ModelEvent> =
                        flow { throw java.net.SocketTimeoutException("timed out") }
                }
            val health = ProviderHealth(Clock { testScheduler.currentTime })
            val chat = AgentTask("Hello", scopes, conversation = true)
            val run = runner(health).start(chat, RunModel(throwing, "gemini", label = "Gemini"), AgentBudgets(), backgroundScope)

            val waiting = run.state.first { it.status == RunStatus.WAITING }

            assertTrue(waiting.outcome!!.contains("NETWORK"), waiting.outcome)
            assertTrue(run.state.value.open, "the chat is still there for the owner's next message")
            assertEquals(
                ProviderState.DOWN,
                health.status.value
                    .getValue("Gemini")
                    .state,
            )
            run.stop()
        }

    @Test
    fun `runs the session as the owner's agent run`() =
        runTest {
            val provider = ScriptedProvider(say("Nothing to do"))
            val ended = mutableListOf<String>()
            val clock = Clock { testScheduler.currentTime }
            val registry = ToolRegistry(setOf(info, snapshot), PolicyProfile.OSS)
            val audit = InMemoryAuditSink()
            val dispatcher = CallDispatcher(registry, audit, clock = clock)
            val runner = AgentRunner(registry, dispatcher, SessionFactory(clock), clock = clock, onSessionEnd = { ended += it.value })
            val run = runner.start(AgentTask("Check", scopes), RunModel(provider, "fake-1"), AgentBudgets(), backgroundScope)
            run.finished()
            testScheduler.advanceUntilIdle()
            assertEquals(listOf(run.id), ended)
            assertTrue(audit.recent.value.all { it.sessionId == run.id && it.principal == SessionFactory.OWNER_PRINCIPAL })
        }

    @Test
    fun `passes the tools the owner allowed to the policy for the run's session`() =
        runTest {
            val clock = Clock { testScheduler.currentTime }
            val registry = ToolRegistry(setOf(info, snapshot), PolicyProfile.OSS)
            val confirmed = mutableListOf<Pair<String, Set<String>>>()
            val runner =
                AgentRunner(
                    registry,
                    CallDispatcher(registry, InMemoryAuditSink(), clock = clock),
                    SessionFactory(clock),
                    clock = clock,
                    preConfirm = { id, tools -> confirmed += id.value to tools },
                )
            val run =
                runner.start(
                    AgentTask("Light mode", scopes, preApproved = setOf("settings.set")),
                    RunModel(ScriptedProvider(say("Done")), "fake-1"),
                    AgentBudgets(),
                    backgroundScope,
                )
            run.finished()
            assertEquals(listOf(run.id to setOf("settings.set")), confirmed)
        }

    @Test
    fun `refuses a second run while one is active`() =
        runTest {
            val provider = ScriptedProvider({ awaitCancellation() })
            val runner = runner()
            val run = runner.start(AgentTask("Wait", scopes), RunModel(provider, "fake-1"), AgentBudgets(), backgroundScope)
            assertFailsWith<IllegalStateException> {
                runner.start(AgentTask("Again", scopes), RunModel(provider, "fake-1"), AgentBudgets(), backgroundScope)
            }
            run.stop()
            assertEquals(RunStatus.STOPPED, run.finished().status)
        }

    @Test
    fun `sends screenshots to vision models and keeps at most the budget`() =
        runTest {
            val provider = ScriptedProvider(callTool("screen_capture"), callTool("screen_capture"), callTool("screen_capture"), say("Done"))
            val run =
                runner().start(
                    AgentTask("Look", scopes),
                    RunModel(provider, "fake-1", vision = true),
                    AgentBudgets(maxImagesPerRequest = 2),
                    backgroundScope,
                )
            val end = run.finished()
            assertEquals(RunStatus.FINISHED, end.status)
            assertEquals("0123456789abcdef", end.steps.first().screenHash)
            val first =
                provider.requests
                    .first()
                    .messages
                    .single()
            assertEquals(1, first.parts.count { it is Part.Image }, "the opening message carries a screenshot")
            val last = provider.requests.last().messages
            assertEquals(2, last.sumOf(::images))
            assertTrue(last.first().parts.any { it is Part.Text && it.text == ImageBudget.PLACEHOLDER })
        }

    @Test
    fun `uses JSON actions for models without tool calling, retrying unparseable replies`() =
        runTest {
            val provider =
                ScriptedProvider(
                    say("I will tap now"),
                    say(
                        """```json
                        |{"action": "ui.tap", "args": {"x": 5}}
                        |```
                        """.trimMargin(),
                    ),
                    say("""{"action": "done", "args": {"summary": "Tapped it"}}"""),
                )
            val run =
                runner().start(
                    AgentTask("Tap", scopes),
                    RunModel(provider, "fake-1", toolMode = ToolMode.JSON_FALLBACK),
                    AgentBudgets(),
                    backgroundScope,
                )
            val end = run.finished()
            assertEquals(RunStatus.FINISHED, end.status)
            assertEquals("Tapped it", end.outcome)
            assertEquals(1, tap.calls.size)
            assertTrue(provider.requests.all { it.tools.isEmpty() })
            assertTrue("ui.tap" in provider.requests.first().system)
        }

    @Test
    fun `fails after three steps in a row fail`() =
        runTest {
            val provider = ScriptedProvider(callTool("ui_swipe"), callTool("nope"), callTool("ui_swipe"))
            val end = runner().start(AgentTask("Swipe", scopes), RunModel(provider, "fake-1"), AgentBudgets(), backgroundScope).finished()
            assertEquals(RunStatus.FAILED, end.status)
            assertEquals(3, end.steps.size)
            assertTrue(end.steps.all { it.calls.single().isError })
        }

    @Test
    fun `stops at the step budget`() =
        runTest {
            val provider = ScriptedProvider(callTool("ui_tap"), callTool("ui_tap"))
            val end =
                runner()
                    .start(
                        AgentTask("Tap", scopes),
                        RunModel(provider, "fake-1"),
                        AgentBudgets(maxSteps = 2),
                        backgroundScope,
                    ).finished()
            assertEquals(RunStatus.OUT_OF_BUDGET, end.status)
            assertEquals(2, tap.calls.size)
        }

    @Test
    fun `stops at the cost budget`() =
        runTest {
            val provider = ScriptedProvider(callTool("ui_tap"))
            val model = RunModel(provider, "fake-1", estimateCost = { input, _ -> input / 100.0 })
            val end = runner().start(AgentTask("Tap", scopes), model, AgentBudgets(maxCostUsd = 0.50), backgroundScope).finished()
            assertEquals(RunStatus.OUT_OF_BUDGET, end.status)
            assertTrue("cost" in end.outcome!!)
        }

    @Test
    fun `stops at the time budget while the model is thinking`() =
        runTest {
            val provider = ScriptedProvider({ awaitCancellation() })
            val end =
                runner()
                    .start(
                        AgentTask("Think", scopes),
                        RunModel(provider, "fake-1"),
                        AgentBudgets(maxWallTimeMs = 5_000),
                        backgroundScope,
                    ).finished()
            assertEquals(RunStatus.OUT_OF_BUDGET, end.status)
            assertEquals(5_000L, end.endedAtMillis!! - end.startedAtMillis)
        }

    @Test
    fun `retries a rate limit, then gives up on an auth error`() =
        runTest {
            val provider = ScriptedProvider(fail(ModelErrorCode.RATE_LIMIT), callTool("ui_tap"), fail(ModelErrorCode.AUTH))
            val end = runner().start(AgentTask("Tap", scopes), RunModel(provider, "fake-1"), AgentBudgets(), backgroundScope).finished()
            assertEquals(RunStatus.FAILED, end.status)
            assertTrue("AUTH" in end.outcome!!)
            assertEquals(1, tap.calls.size)
            assertTrue(end.steps.any { it.note?.contains("retrying") == true })
        }

    @Test
    fun `a takeover drops the pending plan and resumes with a fresh snapshot`() =
        runTest {
            val thinking = CompletableDeferred<Unit>()
            val provider =
                ScriptedProvider(
                    {
                        thinking.await()
                        listOf(ModelEvent.ToolCall("c1", "ui_tap", "{}"), ModelEvent.Stop(StopReason.TOOL_USE))
                    },
                    say("Done after takeover"),
                )
            val run = runner().start(AgentTask("Tap", scopes), RunModel(provider, "fake-1"), AgentBudgets(), backgroundScope)
            testScheduler.runCurrent()
            run.pause()
            screen = "Settings screen"
            thinking.complete(Unit)
            assertEquals(RunStatus.PAUSED, run.state.first { it.status == RunStatus.PAUSED }.status)
            assertNull(run.state.value.outcome)
            run.resume()
            val end = run.finished()

            assertEquals(RunStatus.FINISHED, end.status)
            assertEquals(0, tap.calls.size, "the plan made before the takeover is not carried out")
            val resumed =
                provider.requests
                    .last()
                    .messages
                    .single()
                    .parts
                    .filterIsInstance<Part.Text>()
                    .last()
                    .text
            assertTrue("Settings screen" in resumed)
        }

    @Test
    fun `a chat answers, waits for the next message and keeps the conversation`() =
        runTest {
            val provider = ScriptedProvider(say("Hi, how can I help?"), callTool("ui_tap"), say("Tapped it"))
            val chat = AgentTask("Hello", scopes, conversation = true)
            val run = runner().start(chat, RunModel(provider, "fake-1"), AgentBudgets(), backgroundScope)

            val first = run.answered(1)
            assertEquals("Hi, how can I help?", first.outcome)
            assertEquals("Hi, how can I help?", first.steps.last().reply)
            assertEquals(0, snapshot.calls.size, "a chat does not read the screen before it is asked to")
            assertTrue(
                provider.requests
                    .first()
                    .system
                    .contains("This is a chat"),
            )

            assertTrue(run.send("Tap the button"))
            val second = run.answered(2)
            assertEquals("Tapped it", second.outcome)
            assertEquals("Tap the button", second.steps.first { it.user != null }.user)
            assertEquals(1, tap.calls.size)
            val history = provider.requests[1].messages
            assertEquals(listOf(Role.USER, Role.ASSISTANT, Role.USER), history.map(Message::role))
            assertEquals("Hi, how can I help?", (history[1].parts.single() as Part.Text).text)
            assertTrue((history[2].parts.single() as Part.Text).text.endsWith("Tap the button"))

            run.close()
            val end = run.ended()
            assertEquals(RunStatus.FINISHED, end.status)
            assertEquals("Tapped it", end.outcome)
            assertTrue(end.endedAtMillis != null)
        }

    @Test
    fun `a finished chat turn keeps only its answer in the history`() =
        runTest {
            val provider = ScriptedProvider(callTool("ui_tap"), say("Tapped it"), say("You're welcome"))
            val run =
                runner().start(
                    AgentTask("Tap the button", scopes, conversation = true),
                    RunModel(provider, "fake-1"),
                    AgentBudgets(),
                    backgroundScope,
                )
            run.answered(1)
            assertTrue(run.send("Thanks"))
            run.answered(2)
            val history = provider.requests.last().messages
            assertEquals(listOf(Role.USER, Role.ASSISTANT, Role.USER), history.map(Message::role))
            assertEquals("Tapped it", (history[1].parts.single() as Part.Text).text)
            assertTrue(history.none { message -> message.parts.any { it is Part.ToolCall || it is Part.ToolResult } })
        }

    @Test
    fun `each chat turn gets the full step budget, and a turn that runs out leaves the chat open`() =
        runTest {
            val provider = ScriptedProvider(callTool("ui_tap"), callTool("ui_tap"))
            val chat = AgentTask("Tap twice", scopes, conversation = true)
            val run = runner().start(chat, RunModel(provider, "fake-1"), AgentBudgets(maxSteps = 1), backgroundScope)

            val first = run.state.first { it.status == RunStatus.WAITING }
            assertEquals("Reached the limit of 1 steps", first.outcome)
            assertTrue(run.send("Go on"))
            run.state.first { it.status == RunStatus.WAITING && it.steps.size > first.steps.size + 2 }
            assertEquals(2, tap.calls.size)
            val history = provider.requests[1].messages
            assertEquals(listOf(Role.USER, Role.ASSISTANT, Role.USER), history.map(Message::role))
            assertTrue(
                history
                    .last()
                    .parts
                    .filterIsInstance<Part.Text>()
                    .single()
                    .text
                    .endsWith("Go on"),
                "the message joins the last tool results",
            )
            run.stop()
            assertEquals(RunStatus.STOPPED, run.ended().status)
        }

    @Test
    fun `steps out of the way before screen tools only`() =
        runTest {
            var calls = 0
            val provider = ScriptedProvider(say("Hello"), callTool("ui_tap"), say("Done"))
            val chat = AgentTask("Hi", scopes + Scope.SCREEN_READ, conversation = true)
            val run = runner(beforeScreenWork = { calls++ }).start(chat, RunModel(provider, "fake-1"), AgentBudgets(), backgroundScope)
            run.answered(1)
            assertEquals(0, calls, "device.info is not a screen tool")
            run.send("Tap")
            run.answered(2)
            assertEquals(1, calls)
        }

    @Test
    fun `a new run ends a waiting chat, and only a waiting chat takes messages`() =
        runTest {
            val provider = ScriptedProvider(say("Hello"), say("Done"))
            val runner = runner()
            val chat =
                runner.start(
                    AgentTask("Hi", scopes, conversation = true),
                    RunModel(provider, "fake-1"),
                    AgentBudgets(),
                    backgroundScope,
                )
            chat.answered(1)
            val task = runner.start(AgentTask("Task", scopes), RunModel(provider, "fake-1"), AgentBudgets(), backgroundScope)
            assertEquals(RunStatus.FINISHED, chat.ended().status)
            assertTrue(!chat.send("Still there?"))
            task.finished()
            assertTrue(!task.send("More"), "a one-shot task takes no messages")
        }

    private fun images(message: Message): Int = message.parts.sumOf(::images)

    private fun images(part: Part): Int =
        when (part) {
            is Part.Image -> 1
            is Part.ToolResult -> part.parts.sumOf(::images)
            else -> 0
        }
}
