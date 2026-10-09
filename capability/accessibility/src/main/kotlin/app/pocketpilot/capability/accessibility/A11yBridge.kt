package app.pocketpilot.capability.accessibility

import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import app.pocketpilot.core.model.ToolErrorCode
import app.pocketpilot.core.model.ToolException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Where the running [PocketPilotA11yService] and its event stream meet the backends. The system
 * creates the service, so there is exactly one, and this object is how the rest of the app finds it.
 */
object A11yBridge {
    private val current = MutableStateFlow<PocketPilotA11yService?>(null)
    private val changes = MutableStateFlow(0L)

    @Volatile
    private var lastChangeAt = 0L

    @Volatile
    private var lastWindowClass: Pair<String, String>? = null

    private val connectedFlow = MutableStateFlow(false)

    /** True while the owner has the service turned on. */
    val connected: StateFlow<Boolean> = connectedFlow.asStateFlow()

    internal fun attach(service: PocketPilotA11yService) {
        current.value = service
        connectedFlow.value = true
    }

    internal fun detach(service: PocketPilotA11yService) {
        if (current.compareAndSet(service, null)) connectedFlow.value = false
    }

    internal fun onEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString()
            val cls = event.className?.toString()
            if (pkg != null && cls != null) lastWindowClass = pkg to cls
        }
        lastChangeAt = SystemClock.uptimeMillis()
        changes.value += 1
    }

    /** The running service, or CAPABILITY_UNAVAILABLE with a hint that says how to turn it on. */
    internal fun require(): PocketPilotA11yService =
        current.value ?: throw ToolException(
            ToolErrorCode.CAPABILITY_UNAVAILABLE,
            "PocketPilot's Accessibility service is off, so it cannot read or touch the screen",
            "Ask the phone's owner to turn on PocketPilot in Settings > Accessibility",
        )

    /** The class of the last window that opened in [packageName], usually its activity. */
    internal fun windowClassOf(packageName: String): String? = lastWindowClass?.takeIf { it.first == packageName }?.second

    internal suspend fun awaitIdle(
        quietMs: Long,
        timeoutMs: Long,
    ): Boolean =
        withTimeoutOrNull(timeoutMs) {
            while (true) {
                val quietFor = SystemClock.uptimeMillis() - lastChangeAt
                if (quietFor >= quietMs) break
                delay(quietMs - quietFor)
            }
            true
        } ?: false

    internal suspend fun awaitChange(
        timeoutMs: Long,
        quietMs: Long,
    ): Boolean {
        val start = changes.value
        withTimeoutOrNull(timeoutMs) { changes.first { it > start } } ?: return false
        awaitIdle(quietMs, timeoutMs)
        return true
    }
}
