package app.pocketpilot.core.policy

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
import kotlin.test.assertTrue

class ToolModesTest {
    private val session = Session(SessionId("01JA0000000000000000000000"), SessionKind.MCP_CLIENT, "Claude", Scope.KNOWN, 0)
    private val asked = mutableListOf<String?>()
    private val modes = HashMap<String, ToolMode>()

    private fun engine() =
        PolicyEngine(
            appPolicy = AppPolicy(),
            foreground = { "com.android.settings" },
            confirmer = { request ->
                asked += request.detail ?: request.tool
                ConfirmationAnswer.APPROVED
            },
            toolModes = { modes[it] },
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
    private val settings = spec("settings.set", RiskTier.SENSITIVE, Scope.SETTINGS_WRITE)
    private val shell = spec("shell.exec", RiskTier.SENSITIVE, Scope.SHELL_EXEC)
    private val clear = spec("app.clear_data", RiskTier.DESTRUCTIVE, Scope.APPS_CONTROL)

    @Test
    fun `the tier caps the modes the owner can choose`() {
        assertEquals(ToolMode.entries.toSet(), ToolModes.allowedModes(snapshot))
        assertEquals(ToolMode.entries.toSet(), ToolModes.allowedModes(settings))
        assertEquals(setOf(ToolMode.ASK, ToolMode.BLOCK), ToolModes.allowedModes(clear))
        assertEquals(setOf(ToolMode.ASK, ToolMode.BLOCK), ToolModes.allowedModes(shell))
        assertEquals(ToolMode.ALLOW, ToolModes.defaultMode(tap))
        assertEquals(ToolMode.ASK, ToolModes.defaultMode(settings))
    }

    @Test
    fun `a blocked tool is refused`() =
        runTest {
            modes["screen.snapshot"] = ToolMode.BLOCK
            val deny = assertIs<Decision.Deny>(engine().before(session, snapshot))
            assertEquals(ToolErrorCode.DENIED_BY_POLICY, deny.code)
            assertTrue("screen.snapshot" in deny.message)
            assertTrue(asked.isEmpty())
        }

    @Test
    fun `ask confirms a read tool once per session and exact change`() =
        runTest {
            modes["screen.snapshot"] = ToolMode.ASK
            val engine = engine()
            assertEquals(Decision.Allow, engine.before(session, snapshot))
            assertEquals(Decision.Allow, engine.before(session, snapshot))
            assertEquals(listOf<String?>("screen.snapshot"), asked)
            val other = session.copy(id = SessionId("01JA0000000000000000000001"))
            engine.before(other, snapshot)
            assertEquals(2, asked.size, "a new session asks again")
        }

    @Test
    fun `allow skips the confirmation of a sensitive tool`() =
        runTest {
            modes["settings.set"] = ToolMode.ALLOW
            assertEquals(Decision.Allow, engine().before(session, settings, detail = "global airplane_mode_on 1"))
            assertTrue(asked.isEmpty())
        }

    @Test
    fun `allow never weakens a destructive tool or the shell`() =
        runTest {
            modes["app.clear_data"] = ToolMode.ALLOW
            modes["shell.exec"] = ToolMode.ALLOW
            val engine = engine()
            engine.before(session, clear)
            engine.before(session, clear)
            engine.before(session, shell, detail = "ls")
            engine.before(session, shell, detail = "ls")
            assertEquals(listOf<String?>("app.clear_data", "app.clear_data", "ls"), asked)
        }

    @Test
    fun `without modes every tool keeps its tier default`() =
        runTest {
            val engine = engine()
            assertEquals(Decision.Allow, engine.before(session, tap))
            engine.before(session, settings)
            engine.before(session, settings)
            assertEquals(listOf<String?>("settings.set"), asked)
        }
}
