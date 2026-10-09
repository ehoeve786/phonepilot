package app.pocketpilot.core.model

/** Who is acting on the phone (spec section 4). */
enum class SessionKind {
    MCP_CLIENT,
    AGENT_RUN,
    PLUGIN,
    LOCAL_UI,
}

/** A client, agent run or plugin calling tools; every call is checked against [grantedScopes]. */
data class Session(
    val id: SessionId,
    val kind: SessionKind,
    /** Client ID, plugin ID or `owner`. */
    val principal: String,
    val grantedScopes: Set<Scope>,
    val createdAtMillis: Long,
)
