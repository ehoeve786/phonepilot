package app.pocketpilot.core.automation

import app.pocketpilot.core.audit.AuditDecision
import app.pocketpilot.core.audit.InMemoryAuditSink
import app.pocketpilot.core.common.Clock
import app.pocketpilot.core.model.Availability
import app.pocketpilot.core.model.Flavor
import app.pocketpilot.core.model.Phase
import app.pocketpilot.core.model.PolicyProfile
import app.pocketpilot.core.model.RiskTier
import app.pocketpilot.core.model.Scope
import app.pocketpilot.core.model.Session
import app.pocketpilot.core.model.SessionId
import app.pocketpilot.core.model.SessionKind
import app.pocketpilot.core.model.ToolCall
import app.pocketpilot.core.model.ToolResult
import app.pocketpilot.core.model.ToolSpec
import app.pocketpilot.core.orchestrator.CallDispatcher
import app.pocketpilot.core.orchestrator.ToolHandler
import app.pocketpilot.core.orchestrator.ToolRegistry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An automation's actions go through the real dispatcher, so a tool whose scope the automation's
 * session lacks is refused there, audited, and stops the run.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AutomationDispatchTest {
    private class MemoryPersistence : Persistence {
        var text: String? = null

        override fun load() = text

        override fun save(text: String) {
            this.text = text
        }
    }

    private class Tool(
        override val spec: ToolSpec,
    ) : ToolHandler {
        override suspend fun execute(
            call: ToolCall,
            session: Session,
        ) = ToolResult.success("ran ${spec.name}")
    }

    /** Hands every action to [dispatcher] as the app does; the rest is unused here. */
    private class DispatchingHost(
        private val dispatcher: CallDispatcher,
        private val granted: Set<Scope>,
    ) : AutomationHost {
        override suspend fun callTool(
            session: Session,
            tool: String,
            arguments: JsonObject,
        ) = dispatcher.dispatch(session, tool, arguments)

        override fun openSession(
            rule: Rule,
            tools: Set<String>,
        ) = Session(
            id = SessionId(ULID),
            kind = SessionKind.AUTOMATION,
            principal = "Automation: ${rule.name}",
            grantedScopes = granted,
            createdAtMillis = 0,
        )

        override fun closeSession(session: Session) = Unit

        override suspend fun state(key: String): String? = null

        override suspend fun notifications(): List<NotificationInfo> = emptyList()

        override suspend fun native(
            type: String,
            params: Map<String, String>,
        ): String? = null

        override suspend fun askAgent(prompt: String) = AgentOutcome("", 0.0)

        override val sideload = true
    }

    private companion object {
        const val ULID = "01JZ0000000000000000000001"

        fun tool(
            name: String,
            scope: Scope,
        ) = Tool(
            ToolSpec(
                name = name,
                title = name,
                description = "test tool",
                inputSchema = buildJsonObject { put("type", "object") },
                riskTier = RiskTier.READ,
                requiredScopes = setOf(scope),
                availability = Availability(Phase.P0, setOf(Flavor.OSS)),
            ),
        )
    }

    // Monday 2026-10-12 09:30 UTC.
    private val clock = Clock { LocalDateTime.of(2026, 10, 12, 9, 30).toInstant(ZoneOffset.UTC).toEpochMilli() }

    private fun engine(
        scope: TestScope,
        host: AutomationHost,
    ) = AutomationEngine(RuleStore(MemoryPersistence(), clock), host, scope, clock) { ZoneOffset.UTC }

    private fun rule(vararg actions: ActionStep) =
        Rule(
            id = "r1",
            name = "Test",
            triggers = listOf(TriggerSpec("manual")),
            constraints = ConstraintGroup(),
            actions = actions.toList(),
        )

    @Test
    fun `a tool outside the automation's scopes is refused, audited, and stops the run`() =
        runTest {
            val audit = InMemoryAuditSink()
            val dispatcher =
                CallDispatcher(
                    ToolRegistry(
                        setOf(tool("device.info", Scope.DEVICE_READ), tool("app.stop", Scope.APPS_CONTROL)),
                        PolicyProfile.OSS,
                    ),
                    audit,
                    clock = clock,
                )
            // Every known scope except apps:control, which app.stop needs.
            val host = DispatchingHost(dispatcher, granted = Scope.KNOWN - Scope.APPS_CONTROL)

            val record =
                engine(this, host).run(
                    rule(
                        ActionStep("device.info"),
                        ActionStep("app.stop", mapOf("apps" to "com.example")),
                        ActionStep("device.info"),
                    ),
                    "Test",
                )

            assertEquals(RunOutcome.FAILED, record.outcome)
            assertTrue(record.lines[1].contains("lacks the scope apps:control"), record.lines[1])
            val events = audit.recent.value
            // The audit log is newest first; the step after the refused one never reached the dispatcher.
            assertEquals(listOf("app.stop", "device.info"), events.map { it.tool })
            assertEquals(AuditDecision.DENY, events[0].decision)
            assertEquals(AuditDecision.ALLOW, events[1].decision)
        }

    @Test
    fun `an automation with the scope runs the same tool to the end`() =
        runTest {
            val audit = InMemoryAuditSink()
            val dispatcher =
                CallDispatcher(
                    ToolRegistry(setOf(tool("app.stop", Scope.APPS_CONTROL)), PolicyProfile.OSS),
                    audit,
                    clock = clock,
                )
            val host = DispatchingHost(dispatcher, granted = Scope.KNOWN)

            val record = engine(this, host).run(rule(ActionStep("app.stop", mapOf("apps" to "com.example"))), "Test")

            assertEquals(RunOutcome.OK, record.outcome)
            assertEquals(AuditDecision.ALLOW, audit.recent.value.single().decision)
        }
}
