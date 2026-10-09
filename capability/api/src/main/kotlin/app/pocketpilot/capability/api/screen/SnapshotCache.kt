package app.pocketpilot.capability.api.screen

import app.pocketpilot.core.model.ToolErrorCode
import app.pocketpilot.core.model.ToolException

/**
 * The last few snapshots a reader produced, with whatever the backend needs to act on their elements
 * ([T]: live nodes for Accessibility, nothing for Shizuku). Element IDs from older snapshots are stale.
 */
class SnapshotCache<T>(
    private val keep: Int = DEFAULT_KEEP,
) {
    class Entry<T>(
        val snapshot: Snapshot,
        val payload: T,
    )

    private val entries = LinkedHashMap<String, Entry<T>>()

    @Synchronized
    fun put(
        snapshot: Snapshot,
        payload: T,
    ) {
        entries[snapshot.id] = Entry(snapshot, payload)
        while (entries.size > keep) entries.remove(entries.keys.first())
    }

    @Synchronized
    fun contains(snapshotId: String): Boolean = snapshotId in entries

    /** The entry [target] refers to: its snapshot, or the latest when it names none. */
    @Synchronized
    fun entryFor(target: Target.OnElement): Entry<T> =
        (if (target.snapshotId != null) entries[target.snapshotId] else entries.values.lastOrNull())
            ?: throw stale("Snapshot ${target.snapshotId ?: "(none taken yet)"} is no longer current")

    fun element(target: Target.OnElement): Element {
        val entry = entryFor(target)
        return entry.snapshot.element(target.elementId)
            ?: throw stale("Element ${target.elementId} is not in snapshot ${entry.snapshot.id}")
    }

    companion object {
        const val DEFAULT_KEEP = 5

        fun stale(message: String) =
            ToolException(ToolErrorCode.STALE_ELEMENT, message, "Take a new screen.snapshot and use its element IDs")
    }
}
