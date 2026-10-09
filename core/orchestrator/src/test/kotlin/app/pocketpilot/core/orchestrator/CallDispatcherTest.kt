package app.pocketpilot.core.orchestrator

import app.pocketpilot.core.audit.AuditOutcome
import app.pocketpilot.core.audit.InMemoryAuditSink
import app.pocketpilot.core.model.Availability
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
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CallDispatcherTest {
    private val schema =
        Json
            .parseToJsonElement(
                """{"type":"object","properties":{"text":{"type":"string"},"times":{"type":"integer"}},
                |"required":["text"],"additionalProperties":false}
                """.trimMargin(),
            ).jsonObject

    private fun spec(
        name: String,
        scopes: Set<Scope> = setOf(Scope.DEVICE_READ),
        flavors: Set<Flavor> = Flavor.entries.toSet(),
    ) = ToolSpec(
        name = name,
        title = name,
        description = "test tool",
        inputSchema = schema,
        riskTier = RiskTier.READ,
        requiredScopes = scopes,
        availability = Availability(Phase.P0, flavors),
    )

    private class FakeTool(
        override val spec: ToolSpec,
        override val timeoutMs: Long = 1_000,
        val body: suspend (ToolCall) -> ToolResult = { ToolResult.success("echo ${it.arguments["text"]!!.jsonPrimitive.content}") },
    ) : ToolHandler {
        override suspend fun execute(
            call: ToolCall,
            session: Session,
        ) = body(call)
    }

    private val audit = InMemoryAuditSink()
    private val session = SessionFactory().localTokenSession()

    private fun dispatcher(vararg tools: ToolHandler) = CallDispatcher(ToolRegistry(tools.toSet(), PolicyProfile.OSS), audit)

    private fun args(text: String? = "hi") = buildJsonObject { text?.let { put("text", it) } }

    @Test
    fun `runs a valid call and audits it`() =
        runTest {
            val result = dispatcher(FakeTool(spec("test.echo"))).dispatch(session, "test.echo", args())
            assertFalse(result.isError)
            val event = audit.recent.value.single()
            assertEquals(AuditOutcome.SUCCESS, event.outcome)
            assertEquals("test.echo", event.tool)
            assertFalse(event.argumentsRedacted.toString().contains("hi"), "typed text must be redacted")
        }

    @Test
    fun `unknown tools fail with a hint`() =
        runTest {
            val result = dispatcher().dispatch(session, "nope.tool", JsonObject(emptyMap()))
            assertEquals(ToolErrorCode.UNKNOWN_TOOL, result.errorCode)
            assertEquals(
                AuditOutcome.DENIED,
                audit.recent.value
                    .single()
                    .outcome,
            )
        }

    @Test
    fun `missing scope is denied`() =
        runTest {
            val result = dispatcher(FakeTool(spec("test.shell", setOf(Scope.SHELL_EXEC)))).dispatch(session, "test.shell", args())
            assertEquals(ToolErrorCode.DENIED_BY_POLICY, result.errorCode)
        }

    @Test
    fun `invalid arguments are rejected before the tool runs`() =
        runTest {
            var ran = false
            val tool =
                FakeTool(spec("test.echo")) {
                    ran = true
                    ToolResult.success("x")
                }
            val d = dispatcher(tool)
            assertEquals(ToolErrorCode.INVALID_ARGUMENTS, d.dispatch(session, "test.echo", args(null)).errorCode)
            val wrongType = buildJsonObject { put("text", 3) }
            assertEquals(ToolErrorCode.INVALID_ARGUMENTS, d.dispatch(session, "test.echo", wrongType).errorCode)
            val extra =
                buildJsonObject {
                    put("text", "a")
                    put("other", 1)
                }
            assertEquals(ToolErrorCode.INVALID_ARGUMENTS, d.dispatch(session, "test.echo", extra).errorCode)
            assertFalse(ran)
        }

    @Test
    fun `slow tools time out`() =
        runTest {
            val tool = FakeTool(spec("test.slow"), timeoutMs = 50) { awaitCancellation() }
            assertEquals(ToolErrorCode.TIMEOUT, dispatcher(tool).dispatch(session, "test.slow", args()).errorCode)
        }

    @Test
    fun `tool exceptions keep their code and hint, other exceptions become internal errors`() =
        runTest {
            val busy = FakeTool(spec("test.busy")) { throw ToolException(ToolErrorCode.DEVICE_BUSY, "busy", "wait") }
            val broken = FakeTool(spec("test.broken")) { error("boom") }
            val d = dispatcher(busy, broken)
            val busyResult = d.dispatch(session, "test.busy", args())
            assertEquals(ToolErrorCode.DEVICE_BUSY, busyResult.errorCode)
            assertEquals("wait", busyResult.recoveryHint)
            assertEquals(ToolErrorCode.INTERNAL_ERROR, d.dispatch(session, "test.broken", args()).errorCode)
        }

    @Test
    fun `registry hides tools not built for the flavor`() {
        val playOnly = FakeTool(spec("test.play", flavors = setOf(Flavor.PLAY)))
        val registry = ToolRegistry(setOf(playOnly, FakeTool(spec("test.all"))), PolicyProfile.OSS)
        assertEquals(listOf("test.all"), registry.specs.map { it.name })
    }

    @Test
    fun `local token session never holds shell scopes`() {
        assertTrue(Scope.DEVICE_READ in session.grantedScopes)
        assertFalse(Scope.SHELL_EXEC in session.grantedScopes)
    }
}
