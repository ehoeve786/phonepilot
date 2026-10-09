package app.pocketpilot.core.audit

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Where audit events go. The Room-backed sink with retention arrives with the audit screen. */
interface AuditSink {
    suspend fun record(event: AuditEvent)

    /** Newest first. */
    val recent: StateFlow<List<AuditEvent>>
}

/** Keeps the last [capacity] events in memory. */
class InMemoryAuditSink(
    private val capacity: Int = 500,
) : AuditSink {
    private val events = MutableStateFlow<List<AuditEvent>>(emptyList())

    override val recent: StateFlow<List<AuditEvent>> = events.asStateFlow()

    override suspend fun record(event: AuditEvent) {
        events.update { (listOf(event) + it).take(capacity) }
    }
}
