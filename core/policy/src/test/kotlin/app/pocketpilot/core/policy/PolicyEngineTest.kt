package app.pocketpilot.core.policy

import app.pocketpilot.core.common.Clock
import app.pocketpilot.core.model.Availability
import app.pocketpilot.core.model.Phase
import app.pocketpilot.core.model.RiskTier
import app.pocketpilot.core.model.Scope
import app.pocketpilot.core.model.Session
import app.pocketpilot.core.model.SessionId
import app.pocketpilot.core.model.SessionKind
import app.pocketpilot.core.model.ToolErrorCode
import app.pocketpilot.core.model.ToolSpec
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PolicyEngineTest {
    private val session = Session(SessionId("01JA0000000000000000000000"), SessionKind.MCP_CLIENT, "Claude", Scope.KNOWN, 0)
    private var front: String? = "com.android.settings"
    private val asked = mutableListOf<ConfirmationRequest>()
    private var answer = ConfirmationAnswer.APPROVED

    private fun engine(
        overrides: Map<String, AppMode> = emptyMap(),
        limiter: RateLimiter = RateLimiter(PolicyEngine.DEFAULT_CALLS_PER_MINUTE),
    ) = PolicyEngine(
        appPolicy = AppPolicy({ overrides }, ownPackages = setOf("app.pocketpilot")),
        foreground = { front },
        confirmer = { request ->
            asked += request
            answer
        },
        rateLimiter = limiter,
    )

    private fun spec(
        name: String,
        tier: RiskTier,
        scope: Scope,
    ) = ToolSpec(
        name = name,
        title = name,
        description = name,
        inputSchema = JsonObject(emptyMap()),
        riskTier = tier,
        requiredScopes = setOf(scope),
        availability = Availability(Phase.P1),
    )

    private val snapshot = spec("screen.snapshot", RiskTier.READ, Scope.SCREEN_READ)
    private val tap = spec("ui.tap", RiskTier.INTERACT, Scope.UI_INTERACT)
    private val home = spec("ui.global_action", RiskTier.INTERACT, Scope.UI_INTERACT)

    @Test
    fun `ordinary apps are allowed`() =
        runTest {
            assertEquals(Decision.Allow, engine().before(session, tap))
        }

    @Test
    fun `banking apps can be neither read nor touched, but can be left`() =
        runTest {
            front = "com.td"
            val engine = engine()
            assertEquals(ToolErrorCode.DENIED_BY_POLICY, assertIs<Decision.Deny>(engine.before(session, snapshot)).code)
            assertIs<Decision.Deny>(engine.before(session, tap))
            assertEquals(Decision.Allow, engine.before(session, home))
        }

    @Test
    fun `pocketpilot itself is off limits`() =
        runTest {
            front = "app.pocketpilot"
            assertIs<Decision.Deny>(engine().before(session, tap))
        }

    @Test
    fun `an action that lands in a denied app does not return its screen`() =
        runTest {
            val engine = engine()
            assertEquals(Decision.Allow, engine.before(session, tap))
            front = "com.paypal.android.p2pmobile"
            assertIs<Decision.Deny>(engine.after(tap))
        }

    @Test
    fun `read-only apps allow reading only`() =
        runTest {
            val engine = engine(mapOf("com.android.settings" to AppMode.READ_ONLY))
            assertEquals(Decision.Allow, engine.before(session, snapshot))
            assertIs<Decision.Deny>(engine.before(session, tap))
        }

    @Test
    fun `sensitive tools are confirmed once per session, destructive ones every time`() =
        runTest {
            val engine = engine()
            val sensitive = spec("clipboard.read", RiskTier.SENSITIVE, Scope.CLIPBOARD_RW)
            val destructive = spec("app.stop", RiskTier.DESTRUCTIVE, Scope.APPS_CONTROL)
            engine.before(session, sensitive)
            engine.before(session, sensitive)
            engine.before(session, destructive)
            engine.before(session, destructive)
            assertEquals(listOf("clipboard.read", "app.stop", "app.stop"), asked.map { it.tool })
        }

    @Test
    fun `pre-confirmed sensitive tools skip the prompt, destructive ones never do`() =
        runTest {
            val engine = engine()
            val settings = spec("settings.set", RiskTier.SENSITIVE, Scope.SETTINGS_WRITE)
            val destructive = spec("shell.exec", RiskTier.DESTRUCTIVE, Scope.SHELL_EXEC)
            engine.preConfirm(session.id, setOf("settings.set", "shell.exec"))
            assertEquals(Decision.Allow, engine.before(session, settings))
            assertEquals(Decision.Allow, engine.before(session, destructive))
            assertEquals(listOf("shell.exec"), asked.map { it.tool })
        }

    @Test
    fun `a declined confirmation denies the call`() =
        runTest {
            answer = ConfirmationAnswer.DECLINED
            val decision = engine().before(session, spec("app.stop", RiskTier.DESTRUCTIVE, Scope.APPS_CONTROL))
            assertEquals(ToolErrorCode.CONFIRMATION_DECLINED, assertIs<Decision.Deny>(decision).code)
        }

    @Test
    fun `calls over the rate limit are refused`() =
        runTest {
            val engine = engine(limiter = RateLimiter(2, Clock { 0 }))
            engine.before(session, snapshot)
            engine.before(session, snapshot)
            assertEquals(ToolErrorCode.RATE_LIMITED, assertIs<Decision.Deny>(engine.before(session, snapshot)).code)
        }
}
