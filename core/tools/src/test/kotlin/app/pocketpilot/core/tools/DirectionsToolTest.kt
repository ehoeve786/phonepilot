package app.pocketpilot.core.tools

import app.pocketpilot.capability.api.apps.ActivityEntry
import app.pocketpilot.capability.api.apps.AppAdmin
import app.pocketpilot.capability.api.apps.AppPermissions
import app.pocketpilot.capability.api.screen.Bounds
import app.pocketpilot.capability.api.screen.Element
import app.pocketpilot.capability.api.screen.ForegroundApp
import app.pocketpilot.capability.api.screen.Role
import app.pocketpilot.capability.api.screen.ScreenSize
import app.pocketpilot.capability.api.screen.Snapshot
import app.pocketpilot.core.model.ContentPart
import app.pocketpilot.core.model.ToolCall
import app.pocketpilot.core.model.ToolResult
import app.pocketpilot.core.orchestrator.SessionFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DirectionsToolTest {
    private val box = Bounds(0, 0, 10, 10)

    /** Cards as Google Maps 2026 draws them: an ad for a corner store, an ad for Esso, Chevron and a head office. */
    private val results =
        Snapshot(
            id = "m1",
            packageName = "com.google.android.apps.maps",
            screen = ScreenSize(1080, 2316),
            elements =
                listOf(
                    listOf(
                        Element("e1", role = Role.WINDOW, text = "Maps", bounds = box),
                        Element(
                            "e2",
                            "e1",
                            Role.LIST,
                            resourceId = "$MAPS_ID/search_list_layout",
                            bounds = Bounds(0, 900, 1080, 2316),
                            scrollable = true,
                        ),
                    ),
                    card("c1", "Sponsored", "7-Eleven", "3.5", "Convenience store", "Open 24 hours", "150.0 m", "1199 West Pender Street"),
                    card("c2", "Sponsored", "Esso", "3.6", "Gas station", "Open 24 hours", "1.1 km", "1205 Burrard Street"),
                    card("c3", "Chevron", "4.1", "Gas station", "Open · Closes 10 p.m. · 850.0 m", "1500 West Georgia Street"),
                    card("c4", "Chevron Canada Limited", "Corporate office", "550.0 m", "1050 West Pender Street"),
                    card("c5", "Bathgate Store", "4.5", "Gas station", "77.5 km · Egmont, BC"),
                ).flatten(),
        )

    private companion object {
        const val MAPS_ID = "com.google.android.apps.maps:id"
    }

    private fun card(
        id: String,
        vararg texts: String,
    ): List<Element> =
        listOf(Element(id, "e2", Role.BUTTON, bounds = box, clickable = true)) +
            texts.mapIndexed { i, text -> Element("$id-$i", id, Role.CONTAINER, text = text, description = text, bounds = box) } +
            listOf(
                Element("$id-d", id, Role.BUTTON, description = "Directions", bounds = box, clickable = true),
                Element("$id-dt", "$id-d", Role.CONTAINER, text = "Directions", bounds = box),
            )

    @Test
    fun `reads name, distance and address from each card`() {
        val places = MapsResults.parse(results).associateBy { it.name }
        assertEquals(setOf("7-Eleven", "Esso", "Chevron", "Chevron Canada Limited", "Bathgate Store"), places.keys)
        assertEquals(850.0, places.getValue("Chevron").distanceMeters)
        assertEquals("1500 West Georgia Street", places.getValue("Chevron").address)
        assertEquals("Egmont, BC", places.getValue("Bathgate Store").address)
        assertEquals(77_500.0, places.getValue("Bathgate Store").distanceMeters)
        assertTrue(places.getValue("Esso").sponsored)
    }

    @Test
    fun `picks the closest place of the kind asked for, not a nearer shop or office`() {
        val best = MapsResults.pick(MapsResults.parse(results), "gas station")
        assertEquals("Chevron", best?.name)
    }

    @Test
    fun `a name search matches the name`() {
        assertEquals("Esso", MapsResults.pick(MapsResults.parse(results), "Esso")?.name)
    }

    @Test
    fun `converts miles and feet`() {
        assertEquals(1609.344, MapsResults.meters("1 mi"))
        assertEquals(30.48, MapsResults.meters("100 ft")!!, 0.001)
        assertEquals(1100.0, MapsResults.meters("1,1 km"))
    }

    @Test
    fun `opens the route to the picked place`() =
        runTest {
            val intents = mutableListOf<List<String>>()
            val reader = FakeReader(results).apply { foregroundApp = ForegroundApp("com.google.android.apps.maps", null) }
            val input = FakeInput()
            val tool = AppDirectionsTool(MapsAdmin(intents), reader, input)
            val session = SessionFactory().localTokenSession()
            val call = ToolCall("1", session.id, "app.directions", buildJsonObject { put("query", "gas station") })
            val result = tool.execute(call, session)

            assertEquals(2, intents.size)
            assertTrue(intents[0].contains("geo:0,0?q=gas%20station"), intents[0].toString())
            val route = intents[1].first { it.startsWith("https://") }
            assertEquals(
                "https://www.google.com/maps/dir/?api=1&destination=Chevron%2C%201500%20West%20Georgia%20Street&travelmode=driving",
                route,
            )
            assertTrue(input.calls.first().startsWith("swipeOn e2"))
            assertTrue(
                result.text().startsWith("Showing the driving route to Chevron, 1500 West Georgia Street (850.0 m away)."),
                result.text(),
            )
        }

    private fun ToolResult.text() = content.filterIsInstance<ContentPart.Text>().joinToString("\n") { it.text }

    private class MapsAdmin(
        private val intents: MutableList<List<String>>,
    ) : AppAdmin {
        override val backendId = "fake"
        override val available = MutableStateFlow(true)

        override suspend fun startIntent(
            args: List<String>,
            broadcast: Boolean,
        ): String {
            intents += args
            return "Status: ok"
        }

        override suspend fun forceStop(packageName: String) = Unit

        override suspend fun permissions(packageName: String) = AppPermissions(emptyMap(), emptyMap())

        override suspend fun setPermission(
            packageName: String,
            permission: String,
            granted: Boolean,
        ) = Unit

        override suspend fun setAppOp(
            packageName: String,
            op: String,
            mode: String,
        ) = Unit

        override suspend fun setEnabled(
            packageName: String,
            enabled: Boolean,
        ) = Unit

        override suspend fun clearData(packageName: String) = Unit

        override suspend fun activities(packageName: String) = emptyList<ActivityEntry>()

        override suspend fun setBatteryOptimized(
            packageName: String,
            optimized: Boolean,
        ) = Unit

        override suspend fun setStandbyBucket(
            packageName: String,
            bucket: String,
        ) = Unit
    }
}
