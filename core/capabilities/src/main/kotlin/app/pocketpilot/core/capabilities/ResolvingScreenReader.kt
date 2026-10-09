package app.pocketpilot.core.capabilities

import app.pocketpilot.capability.api.screen.Bounds
import app.pocketpilot.capability.api.screen.ForegroundApp
import app.pocketpilot.capability.api.screen.ScreenReader
import app.pocketpilot.capability.api.screen.Snapshot
import app.pocketpilot.capability.api.screen.SnapshotCache
import app.pocketpilot.capability.api.screen.SnapshotOptions
import app.pocketpilot.capability.api.screen.Target
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * READ_UI across backends in spec priority order (Accessibility, then Shizuku). Element IDs are routed
 * back to whichever backend took the snapshot they came from.
 */
class ResolvingScreenReader(
    private val backends: List<ScreenReader>,
    scope: CoroutineScope,
) : ScreenReader {
    override val backendId: String = "resolver"
    override val available: StateFlow<Boolean> = anyAvailable(scope, backends)

    @Volatile
    private var latest: ScreenReader? = null

    override suspend fun snapshot(options: SnapshotOptions): Snapshot {
        val backend = backends.firstAvailable()
        return backend.snapshot(options).also { latest = backend }
    }

    /** The backend that took the snapshot [target] refers to. */
    fun ownerOf(target: Target.OnElement): ScreenReader {
        val snapshotId = target.snapshotId
        return (if (snapshotId != null) backends.firstOrNull { it.owns(snapshotId) } else latest)
            ?: throw SnapshotCache.stale("Snapshot ${snapshotId ?: "(none taken yet)"} is no longer current")
    }

    override fun bounds(target: Target.OnElement): Bounds = ownerOf(target).bounds(target)

    override fun owns(snapshotId: String): Boolean = backends.any { it.owns(snapshotId) }

    override suspend fun foreground(): ForegroundApp? = backends.firstAvailable().foreground()

    override suspend fun awaitIdle(
        quietMs: Long,
        timeoutMs: Long,
    ): Boolean = backends.firstAvailable().awaitIdle(quietMs, timeoutMs)

    override suspend fun awaitChange(timeoutMs: Long): Boolean = backends.firstAvailable().awaitChange(timeoutMs)
}
