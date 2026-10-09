package app.pocketpilot.core.orchestrator

import app.pocketpilot.core.common.Clock
import app.pocketpilot.core.common.UlidGenerator
import app.pocketpilot.core.model.Scope
import app.pocketpilot.core.model.Session
import app.pocketpilot.core.model.SessionId
import app.pocketpilot.core.model.SessionKind

/** Creates sessions with fresh ULIDs. */
class SessionFactory(
    private val clock: Clock = Clock.System,
    private val ids: UlidGenerator = UlidGenerator(clock),
) {
    /**
     * A session for the per-install local token used by on-device hosts such as Claude Code in Termux.
     * It holds every scope except the raw shell and Termux ones, which stay behind their own toggles.
     */
    fun localTokenSession(): Session =
        Session(
            id = SessionId(ids.next()),
            kind = SessionKind.MCP_CLIENT,
            principal = LOCAL_PRINCIPAL,
            grantedScopes = Scope.KNOWN - setOf(Scope.SHELL_EXEC, Scope.TERMUX_RUN),
            createdAtMillis = clock.nowMillis(),
        )

    /** A session for an OAuth client, holding the scopes the owner granted it. */
    fun clientSession(
        clientName: String,
        grantedScopes: Set<Scope>,
    ): Session =
        Session(
            id = SessionId(ids.next()),
            kind = SessionKind.MCP_CLIENT,
            principal = clientName,
            grantedScopes = grantedScopes,
            createdAtMillis = clock.nowMillis(),
        )

    companion object {
        const val LOCAL_PRINCIPAL = "local-token"
    }
}
