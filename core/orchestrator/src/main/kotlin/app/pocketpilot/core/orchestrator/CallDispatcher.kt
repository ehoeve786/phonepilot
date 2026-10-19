package app.pocketpilot.core.orchestrator

import app.pocketpilot.core.audit.AuditDecision
import app.pocketpilot.core.audit.AuditEvent
import app.pocketpilot.core.audit.AuditOutcome
import app.pocketpilot.core.audit.AuditSink
import app.pocketpilot.core.audit.Redactor
import app.pocketpilot.core.common.Clock
import app.pocketpilot.core.common.UlidGenerator
import app.pocketpilot.core.model.Session
import app.pocketpilot.core.model.ToolCall
import app.pocketpilot.core.model.ToolErrorCode
import app.pocketpilot.core.model.ToolException
import app.pocketpilot.core.model.ToolResult
import app.pocketpilot.core.policy.Decision
import app.pocketpilot.core.policy.PolicyEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject

/**
 * The single entry point for every tool call, from MCP clients, the agent and plugins alike. It runs
 * the call lifecycle from spec section 4: look up, check scopes, validate arguments, ask the policy
 * engine, execute with a timeout, check the policy again, and write one audit event whatever the
 * outcome.
 *
 * Not yet here: the device lease.
 */
class CallDispatcher(
    private val registry: ToolRegistry,
    private val auditSink: AuditSink,
    private val policy: PolicyEngine? = null,
    private val clock: Clock = Clock.System,
    private val ids: UlidGenerator = UlidGenerator(clock),
    private val redactor: Redactor = Redactor(),
) {
    suspend fun dispatch(
        session: Session,
        tool: String,
        arguments: JsonObject,
    ): ToolResult {
        val call = ToolCall(callId = ids.next(), sessionId = session.id, tool = tool, arguments = arguments)
        val started = clock.nowMillis()
        val settled = run(call, session)
        val result = settled.result
        auditSink.record(
            AuditEvent(
                id = call.callId,
                timestampMillis = started,
                sessionId = session.id.value,
                principal = session.principal,
                tool = tool,
                argumentsRedacted = redactor.redact(arguments),
                decision = settled.decision,
                backend = null,
                durationMs = clock.nowMillis() - started,
                outcome =
                    when {
                        settled.decision == AuditDecision.DENY -> AuditOutcome.DENIED
                        settled.withheld -> AuditOutcome.WITHHELD
                        result.isError -> AuditOutcome.ERROR
                        else -> AuditOutcome.SUCCESS
                    },
                errorCode = result.errorCode?.name,
            ),
        )
        return result
    }

    /** What a call ended with: the result returned to the client and whether the call was allowed to run. */
    private class Settled(
        val result: ToolResult,
        val decision: AuditDecision,
        /** Set when the call ran but its result was replaced by a policy refusal afterwards. */
        val withheld: Boolean = false,
    )

    private fun refused(result: ToolResult) = Settled(result, AuditDecision.DENY)

    private suspend fun run(
        received: ToolCall,
        session: Session,
    ): Settled {
        val handler =
            registry.handler(received.tool)
                ?: return refused(
                    ToolResult.error(
                        ToolErrorCode.UNKNOWN_TOOL,
                        "No tool named '${received.tool}'",
                        "Call tools/list for the tools this phone offers.",
                    ),
                )

        val missing = handler.spec.requiredScopes - session.grantedScopes
        if (missing.isNotEmpty()) {
            return refused(
                ToolResult.error(
                    ToolErrorCode.DENIED_BY_POLICY,
                    "This client lacks the scope ${missing.joinToString()} needed for ${received.tool}",
                    "Ask the phone's owner to grant ${missing.joinToString()} to this client.",
                ),
            )
        }

        // Clients with an old copy of a tool's schema, and some models, send "true" or "5" as text.
        val call = received.copy(arguments = ArgumentValidator.coerce(handler.spec.inputSchema, received.arguments))
        ArgumentValidator.validate(handler.spec.inputSchema, call.arguments)?.let { problem ->
            return refused(ToolResult.error(ToolErrorCode.INVALID_ARGUMENTS, problem, "Check the tool's input schema."))
        }

        // A target that cannot be resolved (an unknown label) fails in execute with a proper error instead.
        val target =
            try {
                handler.targetPackage(call)
            } catch (e: ToolException) {
                null
            }
        policy?.before(session, handler.spec, handler.confirmationDetail(call), target)?.let { decision ->
            if (decision is Decision.Deny) {
                return refused(ToolResult.error(decision.code, decision.message, decision.hint))
            }
        }

        val result =
            try {
                withTimeout(handler.timeoutMs) { handler.execute(call, session) }
            } catch (e: TimeoutCancellationException) {
                ToolResult.error(
                    ToolErrorCode.TIMEOUT,
                    "${call.tool} did not finish within ${handler.timeoutMs} ms",
                    "Try again; if it keeps timing out, the phone may be busy.",
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: ToolException) {
                ToolResult.error(e.code, e.message ?: e.code.name, e.recoveryHint)
            } catch (e: Exception) {
                ToolResult.error(ToolErrorCode.INTERNAL_ERROR, "${call.tool} failed: ${e.message ?: e::class.simpleName}")
            }
        // The call has run by now, so the audit records it as allowed and says its result was withheld.
        val after = policy?.after(handler.spec)
        if (after is Decision.Deny) {
            return Settled(ToolResult.error(after.code, after.message, after.hint), AuditDecision.ALLOW, withheld = true)
        }
        return Settled(result, AuditDecision.ALLOW)
    }
}
