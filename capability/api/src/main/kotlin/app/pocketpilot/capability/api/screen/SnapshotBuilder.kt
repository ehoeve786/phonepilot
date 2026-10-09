package app.pocketpilot.capability.api.screen

/** A [Snapshot] plus, for each element ID, the index of the [RawNode] it came from. */
data class BuiltSnapshot(
    val snapshot: Snapshot,
    val sources: Map<String, Int>,
)

/**
 * Turns a raw UI tree into the compact snapshot format of spec section 6: invisible nodes are dropped,
 * empty containers are collapsed, long lists keep their first and last items, and the result is cut
 * at the element limit. Shared by every READ_UI backend so all of them produce the same format.
 */
object SnapshotBuilder {
    const val LONG_LIST = 20
    const val LIST_HEAD = 10
    const val LIST_TAIL = 5

    fun build(
        id: String,
        packageName: String?,
        screen: ScreenSize,
        nodes: List<RawNode>,
        options: SnapshotOptions = SnapshotOptions(),
    ): BuiltSnapshot {
        val children = Array(nodes.size) { mutableListOf<Int>() }
        val roots = mutableListOf<Int>()
        nodes.forEachIndexed { index, node ->
            val parent = node.parent
            if (parent == null || parent !in nodes.indices || parent >= index) roots += index else children[parent] += index
        }

        fun shape(
            index: Int,
            parentRole: Role?,
        ): List<Shaped> {
            val node = nodes[index]
            val isRoot = node.windowTitle != null || node.parent == null
            if (!isRoot && !options.includeInvisible && (!node.visible || node.bounds.isEmpty)) return emptyList()
            val role = roleOf(node, isRoot, parentRole)
            val kids = children[index].flatMap { shape(it, role) }
            val meaningful =
                isRoot || !node.text.isNullOrBlank() || !node.description.isNullOrBlank() || node.actionable || role == Role.LIST
            // Spec section 6: containers with no text and a single child are collapsed; empty ones vanish.
            if (!meaningful && kids.size <= 1) return kids
            if (role == Role.LIST && kids.size > LONG_LIST) {
                val kept = kids.take(LIST_HEAD) + kids.takeLast(LIST_TAIL)
                return listOf(Shaped(index, node, role, kept, omitted = kids.size - kept.size))
            }
            return listOf(Shaped(index, node, role, kids))
        }

        val shaped = roots.flatMap { shape(it, null) }

        val elements = mutableListOf<Element>()
        val sources = mutableMapOf<String, Int>()
        var truncated = false

        fun emit(
            item: Shaped,
            parentId: String?,
        ) {
            if (elements.size >= options.maxElements) {
                truncated = true
                return
            }
            val elementId = "e${elements.size + 1}"
            elements += item.toElement(elementId, parentId)
            sources[elementId] = item.index
            item.children.forEach { emit(it, elementId) }
        }
        shaped.forEach { emit(it, null) }

        return BuiltSnapshot(
            snapshot = Snapshot(id, packageName, screen, elements, truncated),
            sources = sources,
        )
    }

    private class Shaped(
        val index: Int,
        val node: RawNode,
        val role: Role,
        val children: List<Shaped>,
        val omitted: Int? = null,
    )

    private fun Shaped.toElement(
        id: String,
        parentId: String?,
    ): Element =
        Element(
            id = id,
            parent = parentId,
            role = role,
            text = if (node.password) null else (node.text?.takeIf(String::isNotBlank) ?: node.windowTitle),
            description = node.description?.takeIf(String::isNotBlank),
            resourceId = node.resourceId?.takeIf(String::isNotBlank),
            bounds = node.bounds,
            clickable = node.clickable,
            longClickable = node.longClickable,
            editable = node.editable,
            scrollable = node.scrollable,
            checked = if (node.checkable) node.checked else null,
            selected = node.selected,
            focused = node.focused,
            disabled = !node.enabled,
            password = node.password,
            omitted = omitted,
        )

    private val RawNode.actionable: Boolean
        get() = clickable || longClickable || editable || scrollable || checkable

    internal fun roleOf(
        node: RawNode,
        isRoot: Boolean,
        parentRole: Role?,
    ): Role {
        if (isRoot && node.windowTitle != null) return Role.WINDOW
        val name =
            node.className
                .orEmpty()
                .substringAfterLast('.')
                .substringAfterLast('$')
        return when {
            node.editable || name.contains("EditText") -> Role.TEXTFIELD
            name.contains("Switch") || name.contains("ToggleButton") -> Role.SWITCH
            name.contains("CheckBox") || name.contains("RadioButton") || node.checkable -> Role.CHECKBOX
            name.contains("WebView") -> Role.WEBVIEW
            name == "Tab" || name.endsWith("TabView") -> Role.TAB
            name.endsWith("Button") -> Role.BUTTON
            LIST_CLASSES.any(name::endsWith) || node.scrollable -> Role.LIST
            parentRole == Role.LIST -> Role.LISTITEM
            name.endsWith("ImageView") -> if (node.clickable) Role.BUTTON else Role.IMAGE
            name.endsWith("TextView") -> if (node.clickable) Role.BUTTON else Role.TEXT
            node.clickable -> Role.BUTTON
            else -> Role.CONTAINER
        }
    }

    private val LIST_CLASSES = listOf("RecyclerView", "ListView", "GridView", "ScrollView", "ViewPager", "ViewPager2")
}
