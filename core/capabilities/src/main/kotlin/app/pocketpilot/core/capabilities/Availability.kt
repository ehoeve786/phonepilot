package app.pocketpilot.core.capabilities

import app.pocketpilot.capability.api.CapabilityBackend
import app.pocketpilot.core.model.ToolErrorCode
import app.pocketpilot.core.model.ToolException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** True while any of [backends] is available. */
internal fun anyAvailable(
    scope: CoroutineScope,
    backends: List<CapabilityBackend>,
): StateFlow<Boolean> =
    combine(backends.map { it.available }) { states -> states.any { it } }
        .stateIn(scope, SharingStarted.Eagerly, backends.any { it.available.value })

/** The first available backend in priority order, or CAPABILITY_UNAVAILABLE naming how to fix it. */
internal fun <T : CapabilityBackend> List<T>.firstAvailable(): T = firstOrNull { it.available.value } ?: throw unavailable()

internal fun unavailable() =
    ToolException(
        ToolErrorCode.CAPABILITY_UNAVAILABLE,
        "PocketPilot cannot see or touch the screen right now",
        "Ask the phone's owner to turn on PocketPilot in Settings > Accessibility, or to start Shizuku and allow PocketPilot",
    )
