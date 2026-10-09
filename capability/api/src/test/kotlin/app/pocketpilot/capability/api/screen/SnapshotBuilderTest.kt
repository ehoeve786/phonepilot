package app.pocketpilot.capability.api.screen

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SnapshotBuilderTest {
    private val screen = ScreenSize(1080, 2400)
    private val full = Bounds(0, 0, 1080, 2400)

    private fun build(
        nodes: List<RawNode>,
        options: SnapshotOptions = SnapshotOptions(),
    ) = SnapshotBuilder.build("s1", "com.android.settings", screen, nodes, options)

    @Test
    fun `a settings row keeps its clickable container and texts and drops layout wrappers`() {
        val nodes =
            listOf(
                RawNode(parent = null, className = "android.widget.FrameLayout", bounds = full, windowTitle = "Settings"),
                RawNode(parent = 0, className = "android.widget.LinearLayout", bounds = full),
                RawNode(parent = 1, className = "androidx.recyclerview.widget.RecyclerView", bounds = full, scrollable = true),
                RawNode(parent = 2, className = "android.widget.LinearLayout", bounds = Bounds(0, 200, 1080, 350), clickable = true),
                RawNode(parent = 3, className = "android.widget.RelativeLayout", bounds = Bounds(40, 210, 900, 340)),
                RawNode(parent = 4, className = "android.widget.TextView", text = "Display", bounds = Bounds(40, 210, 400, 260)),
                RawNode(
                    parent = 3,
                    className = "android.widget.Switch",
                    bounds = Bounds(950, 250, 1040, 300),
                    checkable = true,
                    checked = false,
                ),
            )
        val snapshot = build(nodes).snapshot
        assertEquals(listOf(Role.WINDOW, Role.LIST, Role.LISTITEM, Role.TEXT, Role.SWITCH), snapshot.elements.map(Element::role))
        val row = snapshot.elements[2]
        assertTrue(row.clickable)
        assertEquals(row.id, snapshot.elements[3].parent, "the text hangs off the row once the wrapper is collapsed")
        assertEquals(false, snapshot.elements[4].checked)
        assertEquals("Settings", snapshot.elements[0].text)
    }

    @Test
    fun `invisible nodes are dropped unless asked for`() {
        val nodes =
            listOf(
                RawNode(parent = null, bounds = full, windowTitle = "App"),
                RawNode(parent = 0, className = "android.widget.TextView", text = "Shown", bounds = Bounds(0, 0, 100, 50)),
                RawNode(
                    parent = 0,
                    className = "android.widget.TextView",
                    text = "Hidden",
                    bounds = Bounds(0, 0, 100, 50),
                    visible = false,
                ),
            )
        assertEquals(listOf("App", "Shown"), build(nodes).snapshot.elements.map { it.text })
        assertEquals(3, build(nodes, SnapshotOptions(includeInvisible = true)).snapshot.elements.size)
    }

    @Test
    fun `password text is never exposed`() {
        val nodes =
            listOf(
                RawNode(parent = null, bounds = full, windowTitle = "Login"),
                RawNode(
                    parent = 0,
                    className = "android.widget.EditText",
                    text = "hunter2",
                    bounds = Bounds(0, 0, 500, 100),
                    editable = true,
                    password = true,
                ),
            )
        val field = build(nodes).snapshot.elements[1]
        assertNull(field.text)
        assertTrue(field.password)
        assertFalse(Json.encodeToString(Snapshot.serializer(), build(nodes).snapshot).contains("hunter2"))
    }

    @Test
    fun `long lists keep the first ten and last five items`() {
        val nodes = mutableListOf(RawNode(parent = null, bounds = full, windowTitle = "List"))
        nodes += RawNode(parent = 0, className = "android.widget.ListView", bounds = full, scrollable = true)
        repeat(30) {
            nodes +=
                RawNode(
                    parent = 1,
                    className = "android.widget.TextView",
                    text = "Item $it",
                    bounds = Bounds(0, it * 10, 1080, it * 10 + 10),
                )
        }
        val elements = build(nodes).snapshot.elements
        val list = elements[1]
        assertEquals(15, list.omitted)
        val items = elements.drop(2).map { it.text }
        assertEquals((0..9).map { "Item $it" } + (25..29).map { "Item $it" }, items)
        assertTrue(elements.drop(2).all { it.role == Role.LISTITEM })
    }

    @Test
    fun `the element limit truncates and maps IDs back to raw nodes`() {
        val nodes = mutableListOf(RawNode(parent = null, bounds = full, windowTitle = "Many"))
        repeat(10) {
            nodes +=
                RawNode(
                    parent = 0,
                    className = "android.widget.Button",
                    text = "B$it",
                    bounds = Bounds(0, it * 10, 100, it * 10 + 10),
                    clickable = true,
                )
        }
        val built = build(nodes, SnapshotOptions(maxElements = 4))
        assertTrue(built.snapshot.truncated)
        assertEquals(4, built.snapshot.elements.size)
        assertEquals(3, built.sources.getValue("e4"))
    }

    @Test
    fun `bounds serialize as a four number array`() {
        val json = Json.encodeToString(Bounds.serializer(), Bounds(1, 2, 3, 4))
        assertEquals("[1,2,3,4]", json)
        assertEquals(Bounds(1, 2, 3, 4), Json.decodeFromString(Bounds.serializer(), json))
    }
}
