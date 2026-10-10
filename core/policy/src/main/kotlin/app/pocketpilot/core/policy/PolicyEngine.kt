package app.pocketpilot.core.policy

import app.pocketpilot.core.model.RiskTier
import app.pocketpilot.core.model.Scope
import app.pocketpilot.core.model.Session
import app.pocketpilot.core.model.SessionId
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
 * Decides allow, deny or confirm for each call (spec section 8), after the orchestrator has checked
 * scopes and arguments:
 *
 * - rate limit per session;
 * - app policy for tools that read or touch the screen, checked against the app in front before the
 *   call and again after it, so an action that lands in a denied app does not return its screen;
 * - confirmation for SENSITIVE tools once per session and DESTRUCTIVE tools on every call.
 *
 * The "PocketPilot is controlling this phone" overlay for INTERACT tools is not here yet.
 */
class PolicyEngine(
    private val appPolicy: AppPolicy,
    /** The package in front, or null when it cannot be read. */
    private val foreground: suspend () -> String?,
    private val confirmer: Confirmer? = null,
    private val rateLimiter: RateLimiter = RateLimiter(DEFAULT_CALLS_PER_MINUTE),
) {
    private val confirmedSensitive = HashMap<SessionId, MutableSet<String>>()

    /** Checks a call before it runs. */
    suspend fun before(
        session: Session,
        spec: ToolSpec,
    ): Decision {
        if (!rateLimiter.tryAcquire(session.id.value)) {
            return Decision.Deny(
                ToolErrorCode.RATE_LIMITED,
                "Too many calls: the limit is $DEFAULT_CALLS_PER_MINUTE a minute per session",
                "Wait a few seconds, then continue more slowly.",
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

        val needsConfirmation =
            confirmBecauseOfApp ||
                spec.riskTier == RiskTier.DESTRUCTIVE ||
                (spec.riskTier == RiskTier.SENSITIVE && !alreadyConfirmed(session.id, spec.name))
        if (needsConfirmation) {
            val answer =
                confirmer?.confirm(
                    ConfirmationRequest(
                        principal = session.principal,
                        tool = spec.name,
                        toolTitle = spec.title,
                        riskTier = spec.riskTier,
                        foregroundPackage = front.takeIf { confirmBecauseOfApp },
                    ),
                ) ?: ConfirmationAnswer.DECLINED
            when (answer) {
                ConfirmationAnswer.APPROVED -> {
                    if (spec.riskTier == RiskTier.SENSITIVE) rememberConfirmed(session.id, spec.name)
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
        return Decision.Allow
    }

    /**
     * Checks the app in front after a screen-touching call. If the call moved the phone into a denied
     * app, the result must not be returned, since it may carry that app's screen.
     */
    suspend fun after(spec: ToolSpec): Decision {
        if (!spec.touchesScreen() || spec.riskTier == RiskTier.READ) return Decision.Allow
        val front = foreground() ?: return Decision.Allow
        return if (appPolicy.modeFor(front) == AppMode.DENY) deniedApp(front) else Decision.Allow
    }

    /**
     * Treats [tools] as already confirmed for session [id], for sessions the owner started on the phone
     * itself. Only sensitive tools are affected; destructive ones are confirmed on every call regardless.
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
    ): Boolean = confirmedSensitive[id]?.contains(tool) == true

    @Synchronized
    private fun rememberConfirmed(
        id: SessionId,
        tool: String,
    ) {
        confirmedSensitive.getOrPut(id) { HashSet() } += tool
    }

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
