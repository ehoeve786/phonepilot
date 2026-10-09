package app.pocketpilot.capability.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import app.pocketpilot.capability.api.screen.Bounds
import app.pocketpilot.capability.api.screen.ForegroundApp
import app.pocketpilot.capability.api.screen.RawNode
import app.pocketpilot.capability.api.screen.ScreenReader
import app.pocketpilot.capability.api.screen.ScreenSize
import app.pocketpilot.capability.api.screen.Snapshot
import app.pocketpilot.capability.api.screen.SnapshotBuilder
import app.pocketpilot.capability.api.screen.SnapshotOptions
import app.pocketpilot.capability.api.screen.Target
import app.pocketpilot.core.model.ToolErrorCode
import app.pocketpilot.core.model.ToolException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/** READ_UI through the Accessibility service: walks every interactive window's node tree. */
class A11yScreenReader : ScreenReader {
    override val available: StateFlow<Boolean> = A11yBridge.connected

    private val counter = AtomicLong()

    /** Recent snapshots, so element IDs from the last few calls still resolve to live nodes. */
    private val recent =
        object : LinkedHashMap<String, Taken>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Taken>) = size > KEEP_SNAPSHOTS
        }

    private class Taken(
        val snapshot: Snapshot,
        val nodes: List<AccessibilityNodeInfo>,
        val sources: Map<String, Int>,
    )

    override suspend fun snapshot(options: SnapshotOptions): Snapshot =
        withContext(Dispatchers.Default) {
            val service = A11yBridge.require()
            val raw = mutableListOf<RawNode>()
            val nodes = mutableListOf<AccessibilityNodeInfo>()
            for (window in orderedWindows(service)) {
                val root = window.root ?: continue
                walk(root, null, window, raw, nodes)
                if (raw.size >= MAX_RAW_NODES) break
            }
            val id = "s${counter.incrementAndGet()}"
            val built = SnapshotBuilder.build(id, appWindow(service)?.root?.packageName?.toString(), screenSize(service), raw, options)
            synchronized(recent) { recent[id] = Taken(built.snapshot, nodes, built.sources) }
            built.snapshot
        }

    override suspend fun foreground(): ForegroundApp? {
        val service = A11yBridge.require()
        val packageName = appWindow(service)?.root?.packageName?.toString() ?: return null
        return ForegroundApp(packageName, A11yBridge.windowClassOf(packageName))
    }

    override suspend fun awaitIdle(
        quietMs: Long,
        timeoutMs: Long,
    ): Boolean = A11yBridge.awaitIdle(quietMs, timeoutMs)

    override suspend fun awaitChange(timeoutMs: Long): Boolean = A11yBridge.awaitChange(timeoutMs, ScreenReader.DEFAULT_QUIET_MS)

    /**
     * The live node behind an element ID, refreshed. Throws STALE_ELEMENT when the snapshot has been
     * dropped or the element has left the screen since.
     */
    internal fun resolve(target: Target.OnElement): AccessibilityNodeInfo {
        val taken =
            synchronized(recent) {
                if (target.snapshotId != null) recent[target.snapshotId] else recent.values.lastOrNull()
            } ?: throw stale("Snapshot ${target.snapshotId ?: "(none taken yet)"} is not current")
        val index = taken.sources[target.elementId] ?: throw stale("Element ${target.elementId} is not in snapshot ${taken.snapshot.id}")
        val node = taken.nodes[index]
        if (!node.refresh()) throw stale("Element ${target.elementId} has left the screen")
        return node
    }

    private fun stale(message: String) =
        ToolException(ToolErrorCode.STALE_ELEMENT, message, "Take a new screen.snapshot and use its element IDs")

    private fun walk(
        node: AccessibilityNodeInfo,
        parent: Int?,
        window: AccessibilityWindowInfo,
        raw: MutableList<RawNode>,
        nodes: MutableList<AccessibilityNodeInfo>,
    ) {
        if (raw.size >= MAX_RAW_NODES) return
        val index = raw.size
        raw += node.toRaw(parent, if (parent == null) window.title?.toString() ?: node.packageName?.toString() ?: "window" else null)
        nodes += node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            walk(child, index, window, raw, nodes)
        }
    }

    private fun AccessibilityNodeInfo.toRaw(
        parent: Int?,
        windowTitle: String?,
    ): RawNode {
        val rect = Rect().also(::getBoundsInScreen)
        return RawNode(
            parent = parent,
            className = className?.toString(),
            text = if (isShowingHintText) null else text?.toString(),
            description = contentDescription?.toString() ?: hintText?.toString()?.let { "hint: $it" },
            resourceId = viewIdResourceName,
            bounds = Bounds(rect.left, rect.top, rect.right, rect.bottom),
            visible = isVisibleToUser,
            clickable = isClickable,
            longClickable = isLongClickable,
            editable = isEditable,
            scrollable = isScrollable,
            checkable = isCheckable,
            checked = isChecked,
            selected = isSelected,
            focused = isFocused,
            enabled = isEnabled,
            password = isPassword,
            windowTitle = windowTitle,
        )
    }

    /** Top-most windows first, so dialogs, the notification shade and the keyboard lead the list. */
    private fun orderedWindows(service: AccessibilityService): List<AccessibilityWindowInfo> =
        service.windows
            .filter { it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }
            .sortedByDescending { it.layer }

    private fun appWindow(service: AccessibilityService): AccessibilityWindowInfo? {
        val apps = service.windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        return apps.firstOrNull { it.isActive || it.isFocused } ?: apps.maxByOrNull { it.layer }
    }

    internal fun screenSize(service: AccessibilityService): ScreenSize {
        val bounds = service.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        return ScreenSize(bounds.width(), bounds.height())
    }

    private companion object {
        const val KEEP_SNAPSHOTS = 5

        /** A safety cap on very deep trees, well above the 300-element default. */
        const val MAX_RAW_NODES = 6_000
    }
}
