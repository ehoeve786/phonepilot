package app.pocketpilot.capability.accessibility

import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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

    private val windowPackage = MutableStateFlow<String?>(null)

    /** The package of the last window that came to the front, for "app opened" automations. */
    val frontPackage: StateFlow<String?> = windowPackage.asStateFlow()

    /** Counts screen events, so watchers can react when the screen changes. */
    val changeCount: StateFlow<Long> = changes.asStateFlow()

    /**
     * The package of the window in front and the texts on it, or null while the service is off.
     * Password fields are left out.
     */
    fun frontWindowText(maxNodes: Int = MAX_TEXT_NODES): Pair<String, List<String>>? {
        val root = current.value?.rootInActiveWindow ?: return null
        val pkg = root.packageName?.toString() ?: return null
        val texts = mutableListOf<String>()
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var visited = 0
        while (queue.isNotEmpty() && visited < maxNodes) {
            val node = queue.removeFirst()
            visited++
            if (!node.isPassword) {
                node.text
                    ?.toString()
                    ?.takeIf { it.isNotBlank() }
                    ?.let(texts::add)
                node.contentDescription
                    ?.toString()
                    ?.takeIf { it.isNotBlank() }
                    ?.let(texts::add)
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
        }
        return pkg to texts
    }

    private const val MAX_TEXT_NODES = 600

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
            if (pkg != null) windowPackage.value = pkg
        }
        lastChangeAt = SystemClock.uptimeMillis()
        changes.value += 1
    }

    /** The running service, or null while it is off. */
    internal fun service(): PocketPilotA11yService? = current.value

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
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        withTimeoutOrNull(timeoutMs) { changes.first { it > start } } ?: return false
        // The quiet wait comes out of the same budget, so the whole call takes at most timeoutMs.
        awaitIdle(quietMs, (deadline - SystemClock.uptimeMillis()).coerceAtLeast(1))
        return true
    }
}
