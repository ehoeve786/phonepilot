package app.pocketpilot.capability.shizuku

import app.pocketpilot.capability.api.screen.Bounds
import app.pocketpilot.capability.api.screen.RawNode

/**
 * Parses `uiautomator dump` XML into [RawNode]s for the shared snapshot builder. The format is flat
 * and machine-written, so a small tag scanner is enough and keeps this testable without Android.
 */
internal object UiAutomatorXml {
    private val TAG = Regex("""<(/?)(node|hierarchy)\b([^>]*?)(/?)>""")
    private val ATTRIBUTE = Regex("""([\w-]+)="([^"]*)"""")
    private val BOUNDS = Regex("""\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)]""")

    fun parse(xml: String): List<RawNode> {
        val nodes = mutableListOf<RawNode>()
        val open = ArrayDeque<Int>()
        for (match in TAG.findAll(xml)) {
            val (closing, name, attributes, selfClosing) = match.destructured
            if (name != "node") continue
            if (closing.isNotEmpty()) {
                open.removeLastOrNull()
                continue
            }
            val attrs = ATTRIBUTE.findAll(attributes).associate { it.groupValues[1] to unescape(it.groupValues[2]) }
            val parent = open.lastOrNull()
            nodes += attrs.toRaw(parent)
            if (selfClosing.isEmpty()) open.addLast(nodes.lastIndex)
        }
        return nodes
    }

    private fun Map<String, String>.toRaw(parent: Int?): RawNode {
        fun flag(name: String) = this[name] == "true"
        val bounds =
            this["bounds"]?.let(BOUNDS::find)?.destructured?.let { (l, t, r, b) -> Bounds(l.toInt(), t.toInt(), r.toInt(), b.toInt()) }
                ?: Bounds(0, 0, 0, 0)
        return RawNode(
            parent = parent,
            className = this["class"],
            text = this["text"]?.takeIf(String::isNotEmpty),
            description = this["content-desc"]?.takeIf(String::isNotEmpty) ?: this["hint"]?.takeIf(String::isNotEmpty)?.let { "hint: $it" },
            resourceId = this["resource-id"]?.takeIf(String::isNotEmpty),
            bounds = bounds,
            visible = this["visible-to-user"] != "false",
            clickable = flag("clickable"),
            longClickable = flag("long-clickable"),
            editable = this["class"]?.endsWith("EditText") == true,
            scrollable = flag("scrollable"),
            checkable = flag("checkable"),
            checked = flag("checked"),
            selected = flag("selected"),
            focused = flag("focused"),
            enabled = this["enabled"] != "false",
            password = flag("password"),
            windowTitle = if (parent == null) this["package"] ?: "window" else null,
        )
    }

    private fun unescape(value: String): String {
        if ('&' !in value) return value
        return Regex("""&(#x?[0-9a-fA-F]+|amp|lt|gt|quot|apos);""").replace(value) { match ->
            when (val entity = match.groupValues[1]) {
                "amp" -> {
                    "&"
                }

                "lt" -> {
                    "<"
                }

                "gt" -> {
                    ">"
                }

                "quot" -> {
                    "\""
                }

                "apos" -> {
                    "'"
                }

                else -> {
                    entity
                        .removePrefix("#")
                        .let { if (it.startsWith("x")) it.drop(1).toInt(16) else it.toInt() }
                        .let { String(Character.toChars(it)) }
                }
            }
        }
    }
}
