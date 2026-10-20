package app.pocketpilot.capability.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import app.pocketpilot.capability.api.BackendIds
import app.pocketpilot.capability.api.screen.Bounds
import app.pocketpilot.capability.api.screen.ForegroundApp
import app.pocketpilot.capability.api.screen.RawNode
import app.pocketpilot.capability.api.screen.ScreenPrivacy
import app.pocketpilot.capability.api.screen.ScreenReader
import app.pocketpilot.capability.api.screen.ScreenSize
import app.pocketpilot.capability.api.screen.Snapshot
import app.pocketpilot.capability.api.screen.SnapshotBuilder
import app.pocketpilot.capability.api.screen.SnapshotCache
import app.pocketpilot.capability.api.screen.SnapshotOptions
import app.pocketpilot.capability.api.screen.Target
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/** READ_UI through the Accessibility service: walks every interactive window's node tree. */
class A11yScreenReader(
    private val privacy: ScreenPrivacy = ScreenPrivacy.NONE,
) : ScreenReader {
    override val backendId: String = BackendIds.ACCESSIBILITY
    override val available: StateFlow<Boolean> = A11yBridge.connected

    private val counter = AtomicLong()

    /**
     * Recent snapshots with their live nodes, so element IDs from the last few calls still resolve. Evicted nodes
     * are not recycled: a caller may still hold one from [resolve], and a recycled node would be read or acted on
     * after its memory was handed out again. The garbage collector takes them instead.
     */
    private val recent = SnapshotCache<Taken>()

    private class Taken(
        val nodes: List<AccessibilityNodeInfo>,
        val sources: Map<String, Int>,
    )

    override suspend fun snapshot(options: SnapshotOptions): Snapshot =
        withContext(Dispatchers.Default) {
            val service = A11yBridge.require()
            val hiddenTexts = privacy.hiddenTexts()
            val raw = mutableListOf<RawNode>()
            val nodes = mutableListOf<AccessibilityNodeInfo>()
            for (window in orderedWindows(service)) {
                val root = window.root ?: continue
                walk(root, null, window, raw, nodes)
                if (raw.size >= MAX_RAW_NODES) break
            }
            val id = "a${counter.incrementAndGet()}"
            val built =
                SnapshotBuilder.build(
                    id,
                    A11yBridge.appPackage(service),
                    screenSize(service),
                    raw,
                    options,
                    privacy::hidesPackage,
                    hiddenTexts,
                )
            recent.put(built.snapshot, Taken(nodes, built.sources))
            built.snapshot
        }

    override suspend fun foreground(): ForegroundApp? {
        val service = A11yBridge.require()
        val packageName = A11yBridge.appPackage(service) ?: return null
        return ForegroundApp(packageName, A11yBridge.windowClassOf(packageName))
    }

    override suspend fun awaitIdle(
        quietMs: Long,
        timeoutMs: Long,
    ): Boolean = A11yBridge.awaitIdle(quietMs, timeoutMs)

    override suspend fun awaitChange(timeoutMs: Long): Boolean = A11yBridge.awaitChange(timeoutMs, ScreenReader.DEFAULT_QUIET_MS)

    override suspend fun hasSelectionBarOf(label: String): Boolean =
        withContext(Dispatchers.Default) {
            val service = A11yBridge.require()
            orderedWindows(service).any { window -> window.root?.let { hasBarUnder(it, label) } == true }
        }

    /**
     * Finds the tab with content description [label] and looks for a bar beside it in the same row. The bar
     * matches the tab's width and ends at the tab's bottom edge, so no screen size is assumed.
     */
    private fun hasBarUnder(
        node: AccessibilityNodeInfo,
        label: String,
    ): Boolean {
        if (node.contentDescription?.toString() == label) {
            val tab = Rect().also(node::getBoundsInScreen)
            val row = node.parent ?: return false
            return (0 until row.childCount).any { i ->
                val sibling = row.getChild(i) ?: return@any false
                val bar = Rect().also(sibling::getBoundsInScreen)
                sibling.text == null &&
                    bar.left == tab.left &&
                    bar.right == tab.right &&
                    bar.bottom == tab.bottom &&
                    bar.height() in 1..SELECTION_BAR_MAX_HEIGHT
            }
        }
        return (0 until node.childCount).any { i -> node.getChild(i)?.let { hasBarUnder(it, label) } == true }
    }

    override fun bounds(target: Target.OnElement): Bounds = recent.element(target).bounds

    override fun owns(snapshotId: String): Boolean = recent.contains(snapshotId)

    /**
     * The live node behind an element ID, refreshed. Throws STALE_ELEMENT when the snapshot has been
     * dropped or the element has left the screen since.
     */
    internal fun resolve(target: Target.OnElement): AccessibilityNodeInfo {
        val entry = recent.entryFor(target)
        val index =
            entry.payload.sources[target.elementId]
                ?: throw SnapshotCache.stale("Element ${target.elementId} is not in snapshot ${entry.snapshot.id}")
        val node = entry.payload.nodes[index]
        if (!node.refresh()) throw SnapshotCache.stale("Element ${target.elementId} has left the screen")
        return node
    }

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
            packageName = packageName?.toString(),
        )
    }

    /** Top-most windows first, so dialogs, the notification shade and the keyboard lead the list. */
    private fun orderedWindows(service: AccessibilityService): List<AccessibilityWindowInfo> =
        service.windows
            .filter { it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }
            .sortedByDescending { it.layer }

    internal fun screenSize(service: AccessibilityService): ScreenSize {
        val bounds = service.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        return ScreenSize(bounds.width(), bounds.height())
    }

    private companion object {
        /** A safety cap on very deep trees, well above the 300-element default. */
        const val MAX_RAW_NODES = 6_000

        /** A tab's selection bar is a thin line; anything taller is not one. */
        const val SELECTION_BAR_MAX_HEIGHT = 12
    }
}
