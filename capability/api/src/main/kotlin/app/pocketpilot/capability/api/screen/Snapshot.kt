package app.pocketpilot.capability.api.screen

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What is on screen, as a flat list of elements with parent links (spec section 6, Snapshot format).
 * Element IDs are only valid against the snapshot that produced them.
 */
@Serializable
data class Snapshot(
    @SerialName("snapshotId") val id: String,
    /** Package of the focused app window, when known. */
    @SerialName("package") val packageName: String?,
    val screen: ScreenSize,
    val elements: List<Element>,
    /** True when the element limit cut the list short. */
    val truncated: Boolean = false,
) {
    fun element(id: String): Element? = elements.firstOrNull { it.id == id }
}

@Serializable
data class ScreenSize(
    val width: Int,
    val height: Int,
)

/** A rectangle in screen pixels. */
@Serializable(with = BoundsSerializer::class)
data class Bounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = left + width / 2
    val centerY: Int get() = top + height / 2
    val isEmpty: Boolean get() = width <= 0 || height <= 0

    fun contains(
        x: Int,
        y: Int,
    ): Boolean = x in left until right && y in top until bottom
}

@Serializable
enum class Role {
    @SerialName("window")
    WINDOW,

    @SerialName("button")
    BUTTON,

    @SerialName("textfield")
    TEXTFIELD,

    @SerialName("text")
    TEXT,

    @SerialName("image")
    IMAGE,

    @SerialName("checkbox")
    CHECKBOX,

    @SerialName("switch")
    SWITCH,

    @SerialName("list")
    LIST,

    @SerialName("listitem")
    LISTITEM,

    @SerialName("tab")
    TAB,

    @SerialName("webview")
    WEBVIEW,

    @SerialName("container")
    CONTAINER,
}

/** One element. Null and false fields are left out of the JSON so snapshots stay small. */
@Serializable
data class Element(
    val id: String,
    val parent: String? = null,
    val role: Role,
    /** Never set for password fields. */
    val text: String? = null,
    @SerialName("desc") val description: String? = null,
    @SerialName("rid") val resourceId: String? = null,
    val bounds: Bounds,
    val clickable: Boolean = false,
    val longClickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    /** Set only for checkable elements. */
    val checked: Boolean? = null,
    val selected: Boolean = false,
    val focused: Boolean = false,
    val disabled: Boolean = false,
    val password: Boolean = false,
    /** For long lists: how many children were left out. */
    val omitted: Int? = null,
)

/** Options for taking a snapshot (`screen.snapshot`). */
data class SnapshotOptions(
    val maxElements: Int = DEFAULT_MAX_ELEMENTS,
    val includeInvisible: Boolean = false,
) {
    companion object {
        const val DEFAULT_MAX_ELEMENTS = 300
    }
}
