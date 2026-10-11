package app.pocketpilot.core.tools

import app.pocketpilot.capability.api.screen.Element
import app.pocketpilot.capability.api.screen.Snapshot
import kotlinx.serialization.builtins.serializer

/**
 * The compact text form of a snapshot that tools return, about half the tokens of JSON with the same
 * information. A header line, then one line per element, indented one space per level below its parent:
 *
 * ```
 * snapshot a36 org.mozilla.firefox 1080x2316
 * e20 window "org.mozilla.firefox" [27,471,1053,1939]
 *  e31 button "Download" #download_button [618,1511,917,1646] click
 * ```
 *
 * A line is: id, role, "text", desc="…", #resource-id (without its package), [left,top,right,bottom],
 * then flags: click, long, edit, scroll, on or off for checkable elements, selected, focused, disabled,
 * password, and omitted=N for long lists cut short.
 */
internal object SnapshotText {
    /** How tool descriptions explain the format to models. */
    const val FORMAT =
        "Plain text: a header (snapshot ID, foreground package, screen size), then one line per element, " +
            "indented one space per level under its parent: id role \"text\" desc=\"...\" #resource-id " +
            "[left,top,right,bottom] and flags (click, long, edit, scroll, on/off, selected, focused, disabled, " +
            "password, omitted=N)."

    fun format(snapshot: Snapshot): String =
        buildString {
            append("snapshot ").append(snapshot.id)
            snapshot.packageName?.let { append(' ').append(it) }
            append(' ').append(snapshot.screen.width).append('x').append(snapshot.screen.height)
            if (snapshot.truncated) append(" truncated")
            val depth = HashMap<String, Int>()
            for (element in snapshot.elements) {
                val level = element.parent?.let { depth[it]?.plus(1) } ?: 0
                depth[element.id] = level
                append('\n')
                repeat(level) { append(' ') }
                append(line(element))
            }
        }

    /** Matches from screen.find and screen.wait_for, without indentation. */
    fun matches(
        snapshotId: String,
        elements: List<Element>,
    ): String = "snapshot $snapshotId\n" + elements.joinToString("\n") { line(it) }

    fun line(element: Element): String =
        buildString {
            append(element.id).append(' ').append(element.role.name.lowercase())
            element.text?.let { append(' ').append(quote(it)) }
            element.description?.let { append(" desc=").append(quote(it)) }
            element.resourceId?.let { append(" #").append(it.substringAfter(":id/")) }
            with(element.bounds) {
                append(" [")
                    .append(left)
                    .append(',')
                    .append(top)
                    .append(',')
            }
            with(element.bounds) { append(right).append(',').append(bottom).append(']') }
            if (element.clickable) append(" click")
            if (element.longClickable) append(" long")
            if (element.editable) append(" edit")
            if (element.scrollable) append(" scroll")
            element.checked?.let { append(if (it) " on" else " off") }
            if (element.selected) append(" selected")
            if (element.focused) append(" focused")
            if (element.disabled) append(" disabled")
            if (element.password) append(" password")
            element.omitted?.let { append(" omitted=").append(it) }
        }

    private fun quote(value: String): String = compactJson.encodeToString(String.serializer(), value)
}
