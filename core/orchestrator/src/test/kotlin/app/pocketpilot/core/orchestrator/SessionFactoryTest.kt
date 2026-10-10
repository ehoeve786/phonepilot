package app.pocketpilot.core.orchestrator

import app.pocketpilot.core.model.Scope
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionFactoryTest {
    @Test
    fun localTokenLeavesOutShellUntilTheOwnerTurnsItOn() {
        var shellOn = false
        val sessions = SessionFactory(localExtraScopes = { if (shellOn) setOf(Scope.SHELL_EXEC) else emptySet() })

        val before = sessions.localTokenSession().grantedScopes
        assertFalse(Scope.SHELL_EXEC in before)
        assertFalse(Scope.TERMUX_RUN in before)

        shellOn = true
        val after = sessions.localTokenSession().grantedScopes
        assertTrue(Scope.SHELL_EXEC in after)
        assertFalse(Scope.TERMUX_RUN in after)
    }
}
