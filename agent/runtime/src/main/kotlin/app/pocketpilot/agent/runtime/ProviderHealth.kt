package app.pocketpilot.agent.runtime

import app.pocketpilot.agent.providers.api.ModelErrorCode
import app.pocketpilot.agent.providers.api.ModelEvent
import app.pocketpilot.core.common.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** How a model in the owner's chain is doing, from the requests Agent mode made to it. */
enum class ProviderState {
    /** Not used since PocketPilot started. */
    UNKNOWN,

    /** Its last request worked. */
    READY,

    /** Rate limited or out of quota; [ProviderStatus.retryAtMillis] says when to try it again. */
    LIMITED,

    /** Refused the key, did not answer or failed on its side. */
    DOWN,
}

/** One model's latest health, keyed by the profile's name. */
data class ProviderStatus(
    val label: String,
    val state: ProviderState,
    /** When this status was recorded. */
    val atMillis: Long,
    /** For [ProviderState.LIMITED], when waiting is over; null when unknown. */
    val retryAtMillis: Long? = null,
    /** The error code behind [ProviderState.LIMITED] and [ProviderState.DOWN]. */
    val error: ModelErrorCode? = null,
)

/**
 * What Agent mode has seen of each model in the chain, for Home's provider card. The runner records every
 * answer and every failure; nothing here makes requests, so showing it costs nothing.
 */
class ProviderHealth(
    private val clock: Clock = Clock.System,
) {
    private val mutableStatus = MutableStateFlow<Map<String, ProviderStatus>>(emptyMap())
    val status: StateFlow<Map<String, ProviderStatus>> = mutableStatus.asStateFlow()

    fun succeeded(label: String) {
        record(ProviderStatus(label, ProviderState.READY, clock.nowMillis()))
    }

    fun failed(
        label: String,
        failure: ModelEvent.Error,
    ) {
        val now = clock.nowMillis()
        val limited = failure.code == ModelErrorCode.RATE_LIMIT || failure.code == ModelErrorCode.QUOTA
        record(
            ProviderStatus(
                label = label,
                state = if (limited) ProviderState.LIMITED else ProviderState.DOWN,
                atMillis = now,
                // A quota is used up for the day or month, so no time is promised.
                retryAtMillis = if (failure.code == ModelErrorCode.RATE_LIMIT) now + (failure.retryAfterMs ?: DEFAULT_LIMIT_MS) else null,
                error = failure.code,
            ),
        )
    }

    private fun record(status: ProviderStatus) {
        mutableStatus.update { it + (status.label to status) }
    }

    private companion object {
        const val DEFAULT_LIMIT_MS = 60_000L
    }
}
