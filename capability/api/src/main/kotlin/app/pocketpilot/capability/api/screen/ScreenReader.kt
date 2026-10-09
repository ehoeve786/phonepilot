package app.pocketpilot.capability.api.screen

import app.pocketpilot.capability.api.CapabilityBackend

/** READ_UI: reads what is on screen. Implemented by the Accessibility service and by Shizuku. */
interface ScreenReader : CapabilityBackend {
    /** Throws [app.pocketpilot.core.model.ToolException] with CAPABILITY_UNAVAILABLE when [available] is false. */
    suspend fun snapshot(options: SnapshotOptions = SnapshotOptions()): Snapshot

    /**
     * Where an element from one of this reader's recent snapshots is, in screen pixels. Throws
     * ToolException STALE_ELEMENT when the snapshot is no longer kept or has no such element.
     */
    fun bounds(target: Target.OnElement): Bounds

    /** True when [snapshotId] is one of this reader's recent snapshots. */
    fun owns(snapshotId: String): Boolean

    /** The app in front, from the most recent window change. */
    suspend fun foreground(): ForegroundApp?

    /**
     * Waits until the screen has stopped changing for [quietMs], or [timeoutMs] passes. Returns true
     * when the screen settled.
     */
    suspend fun awaitIdle(
        quietMs: Long = DEFAULT_QUIET_MS,
        timeoutMs: Long = DEFAULT_IDLE_TIMEOUT_MS,
    ): Boolean

    /**
     * Waits for any screen change after this call, then for the screen to settle. Returns false when
     * nothing changed within [timeoutMs].
     */
    suspend fun awaitChange(timeoutMs: Long): Boolean

    companion object {
        /** Spec section 6: content-change events are debounced by 300 ms to decide the screen is idle. */
        const val DEFAULT_QUIET_MS = 300L
        const val DEFAULT_IDLE_TIMEOUT_MS = 3_000L
    }
}

data class ForegroundApp(
    val packageName: String,
    /** The activity or window class, when the system reports one. */
    val className: String?,
)
