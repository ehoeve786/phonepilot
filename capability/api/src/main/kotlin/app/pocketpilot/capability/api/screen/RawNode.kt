package app.pocketpilot.capability.api.screen

/**
 * One node of a UI tree as a backend read it, before [SnapshotBuilder] turns the tree into a
 * [Snapshot]. Backends list nodes in pre-order and link each to its parent by index.
 */
data class RawNode(
    /** Index of the parent in the same list, or null for a window root. */
    val parent: Int?,
    val className: String? = null,
    val text: String? = null,
    val description: String? = null,
    val resourceId: String? = null,
    val bounds: Bounds,
    val visible: Boolean = true,
    val clickable: Boolean = false,
    val longClickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val selected: Boolean = false,
    val focused: Boolean = false,
    val enabled: Boolean = true,
    val password: Boolean = false,
    /** Set on window roots: the window's title, when it has one. */
    val windowTitle: String? = null,
)
