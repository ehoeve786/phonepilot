package app.pocketpilot.core.orchestrator

import app.pocketpilot.core.model.Session
import app.pocketpilot.core.model.ToolCall
import app.pocketpilot.core.model.ToolResult
import app.pocketpilot.core.model.ToolSpec

/**
 * Runs one tool. Handlers are only ever invoked by [CallDispatcher], after scope checks and argument
 * validation, so they can assume their arguments match [spec]'s input schema.
 */
interface ToolHandler {
    val spec: ToolSpec

    /** Per-call timeout in milliseconds. */
    val timeoutMs: Long get() = DEFAULT_TIMEOUT_MS

    /** Throw [app.pocketpilot.core.model.ToolException] to fail with a specific error code. */
    suspend fun execute(
        call: ToolCall,
        session: Session,
    ): ToolResult

    companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000L
    }
}
