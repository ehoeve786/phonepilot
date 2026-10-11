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
    private val acting = mutableListOf<String>()
    private var stopped = false

    private fun engine(
        overrides: Map<String, AppMode> = emptyMap(),
        limiter: RateLimiter = RateLimiter(PolicyEngine.DEFAULT_CALLS_PER_MINUTE),
        leave: suspend () -> Unit = {},
    ) = PolicyEngine(
        appPolicy = AppPolicy({ overrides }, ownPackages = setOf("app.pocketpilot")),
        foreground = { front },
        confirmer = { request ->
            asked += request
            answer
        },
        rateLimiter = limiter,
        leaveDeniedApp = leave,
        indicator = { acting += it.principal },
        clientsStopped = { stopped },
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
    fun `a denied app is refused before it is opened`() =
        runTest {
            val launch = spec("app.launch", RiskTier.INTERACT, Scope.APPS_CONTROL)
            val engine = engine()
            assertIs<Decision.Deny>(engine.before(session, launch, targetPackage = "com.rbc.mobile.android"))
            assertEquals(Decision.Allow, engine.before(session, launch, targetPackage = "com.android.chrome"))
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
            var left = 0
            val engine = engine(leave = { left++ })
            assertEquals(Decision.Allow, engine.before(session, tap))
            front = "com.paypal.android.p2pmobile"
            assertIs<Decision.Deny>(engine.after(tap))
            assertEquals(1, left, "the phone leaves the denied app")
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
    fun `approving one change of a sensitive tool does not approve another`() =
        runTest {
            val engine = engine()
            val grant = spec("app.set_permission", RiskTier.SENSITIVE, Scope.APPS_CONTROL)
            engine.before(session, grant, detail = "Grant android.permission.CAMERA for com.example")
            engine.before(session, grant, detail = "Grant android.permission.CAMERA for com.example")
            engine.before(session, grant, detail = "Grant android.permission.READ_SMS for com.example")
            assertEquals(
                listOf("Grant android.permission.CAMERA for com.example", "Grant android.permission.READ_SMS for com.example"),
                asked.map { it.detail },
            )
        }

    @Test
    fun `a read that ends in a denied app is withheld without leaving it`() =
        runTest {
            var left = 0
            val engine = engine(leave = { left++ })
            front = "com.td"
            assertIs<Decision.Deny>(engine.after(snapshot))
            assertEquals(0, left)
        }

    @Test
    fun `pre-confirmed sensitive tools skip the prompt, destructive ones never do`() =
        runTest {
            val engine = engine()
            val settings = spec("settings.set", RiskTier.SENSITIVE, Scope.SETTINGS_WRITE)
            val destructive = spec("app.clear_data", RiskTier.DESTRUCTIVE, Scope.APPS_CONTROL)
            engine.preConfirm(session.id, setOf("settings.set", "app.clear_data"))
            assertEquals(Decision.Allow, engine.before(session, settings))
            assertEquals(Decision.Allow, engine.before(session, destructive))
            assertEquals(listOf("app.clear_data"), asked.map { it.tool })
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

    @Test
    fun `the overlay shows for actions, not for reading`() =
        runTest {
            val engine = engine()
            engine.before(session, snapshot)
            engine.before(session, tap)
            front = "com.td"
            engine.before(session, tap)
            assertEquals(listOf("Claude"), acting, "only the allowed tap shows the overlay")
        }

    @Test
    fun `stopped clients are refused, agent runs are not`() =
        runTest {
            stopped = true
            val engine = engine()
            assertIs<Decision.Deny>(engine.before(session, snapshot))
            val agent = session.copy(id = SessionId("01JA0000000000000000000001"), kind = SessionKind.AGENT_RUN)
            assertEquals(Decision.Allow, engine.before(agent, tap))
        }
}
