package app.pocketpilot.core.policy

import app.pocketpilot.core.model.RiskTier
import app.pocketpilot.core.model.Scope
import app.pocketpilot.core.model.Session
import app.pocketpilot.core.model.SessionId
import app.pocketpilot.core.model.SessionKind
import app.pocketpilot.core.model.ToolErrorCode
import app.pocketpilot.core.model.ToolSpec

/** The policy engine's answer for one call. */
sealed interface Decision {
    data object Allow : Decision

    data class Deny(
        val code: ToolErrorCode,
        val message: String,
        val hint: String,
    ) : Decision
}

/** What the owner is asked to confirm on the phone. */
data class ConfirmationRequest(
    /** The client or agent asking, as the owner knows it. */
    val principal: String,
    val tool: String,
    val toolTitle: String,
    val riskTier: RiskTier,
    /** Set when the call is confirmed because of the app in front ([AppMode.CONFIRM_ALL]). */
    val foregroundPackage: String? = null,
    /** What exactly will run, such as a shell command, shown to the owner verbatim. */
    val detail: String? = null,
)

enum class ConfirmationAnswer {
    APPROVED,
    DECLINED,
    TIMED_OUT,
}

/** Shows a confirmation on the phone and waits for the owner. */
fun interface Confirmer {
    suspend fun confirm(request: ConfirmationRequest): ConfirmationAnswer
}

/**
 * Shows the owner who is acting on the phone, with a way to stop them: the "PocketPilot is controlling
 * this phone" overlay (spec section 8). Called before every allowed INTERACT, SENSITIVE or DESTRUCTIVE
 * call from a client or an agent run.
 */
fun interface ControlIndicator {
    fun acting(session: Session)
}

/**
 * Decides allow, deny or confirm for each call (spec section 8), after the orchestrator has checked
 * scopes and arguments:
 *
 * - rate limit per session;
 * - app policy for tools that read or touch the screen, checked against the app in front before the
 *   call and again after it, so an action that lands in a denied app does not return its screen and
 *   the phone goes back to the home screen;
 * - confirmation for SENSITIVE tools once per session and exact change (the tool with its detail, so
 *   approving one permission grant or one file does not approve the next) and DESTRUCTIVE tools on
 *   every call;
 * - the owner's [ToolModes]: BLOCK refuses the tool, ASK confirms it like a SENSITIVE tool whatever its
 *   tier, and ALLOW skips the confirmation of a SENSITIVE tool (never of a DESTRUCTIVE one or shell.exec);
 * - the [ControlIndicator] for every call that acts on the phone, and a refusal of every MCP client
 *   call while the owner has stopped clients from it.
 */
class PolicyEngine(
    private val appPolicy: AppPolicy,
    /** The package in front, or null when it cannot be read. */
    private val foreground: suspend () -> String?,
    private val confirmer: Confirmer? = null,
    private val rateLimiter: RateLimiter = RateLimiter(DEFAULT_CALLS_PER_MINUTE),
    /** Leaves a denied app that a call opened, such as by pressing Home. */
    private val leaveDeniedApp: suspend () -> Unit = {},
    private val indicator: ControlIndicator? = null,
    /** True while the owner has stopped MCP clients, from the overlay's Stop button. */
    private val clientsStopped: () -> Boolean = { false },
    /** The owner's Allow, Ask or Block per tool; null keeps every tool at its tier's default. */
    private val toolModes: ToolModes? = null,
) {
    private val confirmedSensitive = HashMap<SessionId, MutableSet<String>>()

    /** Checks a call before it runs. */
    suspend fun before(
        session: Session,
        spec: ToolSpec,
        /** Shown in the confirmation; see [ConfirmationRequest.detail]. */
        detail: String? = null,
        /** The app the call will open or change, when the tool knows it in advance. */
        targetPackage: String? = null,
    ): Decision {
        if (session.kind == SessionKind.MCP_CLIENT && clientsStopped()) {
            return Decision.Deny(
                ToolErrorCode.DENIED_BY_POLICY,
                "The phone's owner stopped AI apps from using this phone",
                "Do not retry. The owner can allow it again in PocketPilot.",
            )
        }
        val mode = ToolModes.effectiveMode(toolModes, spec)
        if (mode == ToolMode.BLOCK) {
            return Decision.Deny(
                ToolErrorCode.DENIED_BY_POLICY,
                "The owner blocked ${spec.name}",
                "Do not retry. Use another tool, or ask the owner to allow it in PocketPilot's Tools screen.",
            )
        }
        if (!rateLimiter.tryAcquire(session.id.value)) {
            return Decision.Deny(
                ToolErrorCode.RATE_LIMITED,
                "Too many calls: the limit is $DEFAULT_CALLS_PER_MINUTE a minute per session",
                "Wait a few seconds, then continue more slowly.",
            )
        }

        if (targetPackage != null && appPolicy.modeFor(targetPackage) == AppMode.DENY) {
            return Decision.Deny(
                ToolErrorCode.DENIED_BY_POLICY,
                "PocketPilot does not open or change $targetPackage",
                "Banking, payment, password and authenticator apps are off limits. Tell the owner to open it themselves.",
            )
        }

        val front = if (spec.touchesScreen()) foreground() else null
        var confirmBecauseOfApp = false
        if (front != null && spec.name !in LEAVING_TOOLS) {
            when (appPolicy.modeFor(front)) {
                AppMode.ALLOW -> {
                    Unit
                }

                AppMode.DENY -> {
                    return deniedApp(front)
                }

                AppMode.READ_ONLY -> {
                    if (spec.riskTier > RiskTier.READ) {
                        return Decision.Deny(
                            ToolErrorCode.DENIED_BY_POLICY,
                            "$front is read-only for remote control",
                            "Only screen reading is allowed in this app. Ask the phone's owner if you need more.",
                        )
                    }
                }

                AppMode.CONFIRM_ALL -> {
                    confirmBecauseOfApp = spec.riskTier > RiskTier.READ
                }
            }
        }

        // ASK confirms once per session and exact change; a DESTRUCTIVE tool can only be ASK and asks every call.
        val asksOnce = mode == ToolMode.ASK && spec.riskTier != RiskTier.DESTRUCTIVE
        val needsConfirmation =
            confirmBecauseOfApp ||
                spec.riskTier == RiskTier.DESTRUCTIVE ||
                (asksOnce && !alreadyConfirmed(session.id, spec.name, detail))
        if (needsConfirmation) {
            val answer =
                confirmer?.confirm(
                    ConfirmationRequest(
                        principal = session.principal,
                        tool = spec.name,
                        toolTitle = spec.title,
                        riskTier = spec.riskTier,
                        foregroundPackage = front.takeIf { confirmBecauseOfApp },
                        detail = detail,
                    ),
                ) ?: ConfirmationAnswer.DECLINED
            when (answer) {
                ConfirmationAnswer.APPROVED -> {
                    if (asksOnce) rememberConfirmed(session.id, confirmationKey(spec.name, detail))
                }

                ConfirmationAnswer.DECLINED -> {
                    return Decision.Deny(
                        ToolErrorCode.CONFIRMATION_DECLINED,
                        "The phone's owner declined ${spec.name}",
                        "Do not retry this action unless the owner asks for it.",
                    )
                }

                ConfirmationAnswer.TIMED_OUT -> {
                    return Decision.Deny(
                        ToolErrorCode.CONFIRMATION_TIMEOUT,
                        "Nobody answered the confirmation for ${spec.name} on the phone",
                        "Ask the owner to watch the phone, then try again.",
                    )
                }
            }
        }
        if (spec.riskTier > RiskTier.READ && session.kind != SessionKind.LOCAL_UI) indicator?.acting(session)
        return Decision.Allow
    }

    /**
     * Checks the app in front after a screen-touching call. If the call moved the phone into a denied
     * app, the result must not be returned, since it may carry that app's screen. A read that ends in a
     * denied app, such as a wait during which the owner opened their bank, is withheld too, but the
     * phone stays where the owner put it.
     */
    suspend fun after(spec: ToolSpec): Decision {
        if (!spec.touchesScreen()) return Decision.Allow
        val front = foreground() ?: return Decision.Allow
        if (appPolicy.modeFor(front) != AppMode.DENY) return Decision.Allow
        if (spec.riskTier == RiskTier.READ) return deniedApp(front)
        // A tap on a launcher icon or a link can open a denied app; leave it before anything else can act there.
        runCatching { leaveDeniedApp() }
        return Decision.Deny(
            ToolErrorCode.DENIED_BY_POLICY,
            "That action opened $front, which PocketPilot does not read or control, so the phone went back to the home screen",
            "Banking, payment, password and authenticator apps are off limits. Do not open them again.",
        )
    }

    /**
     * Treats [tools] as already confirmed for session [id] whatever their detail, for sessions the owner
     * started on the phone itself. Only sensitive tools are affected; destructive ones are confirmed on
     * every call regardless.
     */
    @Synchronized
    fun preConfirm(
        id: SessionId,
        tools: Set<String>,
    ) {
        confirmedSensitive.getOrPut(id) { HashSet() } += tools
    }

    /** Forgets a session's confirmations and rate-limit window when it ends. */
    @Synchronized
    fun endSession(id: SessionId) {
        confirmedSensitive.remove(id)
        rateLimiter.forget(id.value)
    }

    @Synchronized
    private fun alreadyConfirmed(
        id: SessionId,
        tool: String,
        detail: String?,
    ): Boolean {
        val confirmed = confirmedSensitive[id] ?: return false
        return tool in confirmed || confirmationKey(tool, detail) in confirmed
    }

    @Synchronized
    private fun rememberConfirmed(
        id: SessionId,
        key: String,
    ) {
        confirmedSensitive.getOrPut(id) { HashSet() } += key
    }

    /** A whole tool when it has no detail, else that exact change of it. */
    private fun confirmationKey(
        tool: String,
        detail: String?,
    ): String = if (detail == null) tool else "$tool\u0000$detail"

    private fun deniedApp(front: String) =
        Decision.Deny(
            ToolErrorCode.DENIED_BY_POLICY,
            "PocketPilot does not read or control $front",
            "Banking, payment, password and authenticator apps are off limits. Leave with ui.global_action " +
                "(HOME or BACK) or app.launch another app.",
        )

    /** Tools that read the screen, act on it, or change which app is in front. */
    private fun ToolSpec.touchesScreen(): Boolean = requiredScopes.any { it in SCREEN_SCOPES }

    companion object {
        const val DEFAULT_CALLS_PER_MINUTE = 120

        private val SCREEN_SCOPES = setOf(Scope.SCREEN_READ, Scope.UI_INTERACT, Scope.APPS_CONTROL)

        /** Ways out of a denied app, allowed even while it is in front. */
        private val LEAVING_TOOLS = setOf("ui.global_action", "app.launch", "app.open_url")
    }
}
