package app.pocketpilot.core.capabilities

import app.pocketpilot.capability.api.CapabilityBackend
import app.pocketpilot.core.model.CapabilityId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** One capability, its backends in priority order, and which one serves calls now. */
data class CapabilityStatus(
    val capability: CapabilityId,
    val backends: List<BackendStatus>,
) {
    /** The backend calls go to, or null when the capability is unavailable. */
    val active: String? get() = backends.firstOrNull { it.available }?.backendId
}

data class BackendStatus(
    val backendId: String,
    val available: Boolean,
)

/** What Doctor shows: every capability with the health of each of its backends (spec section 2, core/permissions). */
class CapabilityGraph(
    private val backends: Map<CapabilityId, List<CapabilityBackend>>,
    scope: CoroutineScope,
) {
    val state: StateFlow<List<CapabilityStatus>> =
        combine(backends.values.flatten().map { it.available }) { snapshot() }
            .stateIn(scope, SharingStarted.Eagerly, snapshot())

    fun snapshot(): List<CapabilityStatus> =
        backends.map { (capability, list) ->
            CapabilityStatus(capability, list.map { BackendStatus(it.backendId, it.available.value) })
        }
}
