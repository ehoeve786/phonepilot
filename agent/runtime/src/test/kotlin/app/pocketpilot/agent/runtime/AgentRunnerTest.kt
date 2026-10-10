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

    private fun TestScope.runner(): AgentRunner {
        val clock = Clock { testScheduler.currentTime }
        val registry = ToolRegistry(setOf(info, snapshot, capture, tap, broken, shell), PolicyProfile.OSS)
        return AgentRunner(registry, CallDispatcher(registry, InMemoryAuditSink(), clock = clock), SessionFactory(clock), clock = clock)
    }

    private suspend fun AgentRun.finished(): RunState = state.first { !it.active }

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
                (
                    first.messages
                        .single()
                        .parts
                        .single() as Part.Text
                ).text
            assertTrue("Open the app" in opening && "Galaxy S23 Ultra" in opening && "Home screen" in opening)

            val second = provider.requests[1].messages
            assertEquals(listOf(Role.USER, Role.ASSISTANT, Role.USER), second.map(Message::role))
            val result = second.last().parts.single() as Part.ToolResult
            assertEquals("c-ui_tap", result.id)
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

    private fun images(message: Message): Int = message.parts.sumOf(::images)

    private fun images(part: Part): Int =
        when (part) {
            is Part.Image -> 1
            is Part.ToolResult -> part.parts.sumOf(::images)
            else -> 0
        }
}
