package app.pocketpilot.core.capabilities

import app.pocketpilot.capability.api.apps.AppController
import app.pocketpilot.capability.api.apps.AppInfo
import app.pocketpilot.capability.api.screen.Bounds
import app.pocketpilot.capability.api.screen.Element
import app.pocketpilot.capability.api.screen.ForegroundApp
import app.pocketpilot.capability.api.screen.GlobalAction
import app.pocketpilot.capability.api.screen.InputController
import app.pocketpilot.capability.api.screen.Key
import app.pocketpilot.capability.api.screen.Role
import app.pocketpilot.capability.api.screen.ScreenReader
import app.pocketpilot.capability.api.screen.ScreenSize
import app.pocketpilot.capability.api.screen.Snapshot
import app.pocketpilot.capability.api.screen.SnapshotCache
import app.pocketpilot.capability.api.screen.SnapshotOptions
import app.pocketpilot.capability.api.screen.SwipeDirection
import app.pocketpilot.capability.api.screen.Target
import app.pocketpilot.core.model.CapabilityId
import app.pocketpilot.core.model.ToolErrorCode
import app.pocketpilot.core.model.ToolException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ResolverTest {
    private class Reader(
        override val backendId: String,
        on: Boolean,
    ) : ScreenReader {
        override val available = MutableStateFlow(on)
        private val cache = SnapshotCache<Unit>()
        private var count = 0

        override suspend fun snapshot(options: SnapshotOptions): Snapshot {
            val snapshot =
                Snapshot(
                    "${backendId.first()}${++count}",
                    "pkg",
                    ScreenSize(100, 200),
                    listOf(Element("e1", role = Role.BUTTON, bounds = Bounds(10, 20, 30, 40), clickable = true)),
                )
            cache.put(snapshot, Unit)
            return snapshot
        }

        override fun bounds(target: Target.OnElement) = cache.element(target).bounds

        override fun owns(snapshotId: String) = cache.contains(snapshotId)

        override suspend fun foreground() = ForegroundApp("pkg", null)

        override suspend fun awaitIdle(
            quietMs: Long,
            timeoutMs: Long,
        ) = true

        override suspend fun awaitChange(timeoutMs: Long) = true
    }

    private class Input(
        override val backendId: String,
        on: Boolean,
    ) : InputController {
        override val available = MutableStateFlow(on)
        val calls = mutableListOf<String>()

        override suspend fun tap(target: Target) {
            calls += "tap $target"
        }

        override suspend fun longPress(
            target: Target,
            durationMs: Long,
        ) {
            calls += "long $target"
        }

        override suspend fun swipe(
            fromX: Int,
            fromY: Int,
            toX: Int,
            toY: Int,
            durationMs: Long,
        ) {
            calls += "swipe $fromX,$fromY $toX,$toY"
        }

        override suspend fun swipeOn(
            target: Target.OnElement,
            direction: SwipeDirection,
            durationMs: Long,
        ) {
            calls += "swipeOn ${target.elementId} $direction"
        }

        override suspend fun typeText(
            text: String,
            target: Target.OnElement?,
            submit: Boolean,
        ) {
            calls += "type $text ${target?.elementId}"
        }

        override suspend fun pressKey(key: Key) {
            calls += "key $key"
        }

        override suspend fun globalAction(action: GlobalAction) {
            calls += "global $action"
        }
    }

    private val a11yReader = Reader("accessibility", on = true)
    private val shizukuReader = Reader("shizuku", on = true)
    private val a11yInput = Input("accessibility", on = true)
    private val shizukuInput = Input("shizuku", on = true)

    private fun TestScope.resolvers(): Pair<ResolvingScreenReader, ResolvingInputController> {
        val reader = ResolvingScreenReader(listOf(a11yReader, shizukuReader), backgroundScope)
        return reader to ResolvingInputController(listOf(shizukuInput, a11yInput), reader, backgroundScope)
    }

    @Test
    fun `snapshots come from Accessibility first and Shizuku when it is off`() =
        runTest {
            val (reader, _) = resolvers()
            assertEquals("a1", reader.snapshot().id)
            a11yReader.available.value = false
            assertEquals("s1", reader.snapshot().id)
            shizukuReader.available.value = false
            val error = assertFailsWith<ToolException> { reader.snapshot() }
            assertEquals(ToolErrorCode.CAPABILITY_UNAVAILABLE, error.code)
        }

    @Test
    fun `points go to Shizuku first, elements to the backend that saw them`() =
        runTest {
            val (reader, input) = resolvers()
            reader.snapshot()
            input.tap(Target.AtPoint(5, 6))
            input.tap(Target.OnElement("e1"))
            assertEquals(listOf("tap AtPoint(x=5, y=6)"), shizukuInput.calls)
            assertEquals(listOf("tap OnElement(elementId=e1, snapshotId=null)"), a11yInput.calls)
        }

    @Test
    fun `an element becomes a centre point when its backend cannot act`() =
        runTest {
            val (reader, input) = resolvers()
            reader.snapshot()
            a11yInput.available.value = false
            input.tap(Target.OnElement("e1", "a1"))
            input.swipeOn(Target.OnElement("e1", "a1"), SwipeDirection.UP, 300)
            assertEquals(listOf("tap AtPoint(x=20, y=30)", "swipe 20,37 20,23"), shizukuInput.calls)
        }

    @Test
    fun `unknown snapshots are stale`() =
        runTest {
            val (reader, input) = resolvers()
            reader.snapshot()
            val error = assertFailsWith<ToolException> { input.tap(Target.OnElement("e1", "a9")) }
            assertEquals(ToolErrorCode.STALE_ELEMENT, error.code)
            assertFailsWith<ToolException> { input.tap(Target.OnElement("e7")) }
        }

    @Test
    fun `typing into another backend's element taps it first`() =
        runTest {
            val (reader, input) = resolvers()
            a11yReader.available.value = false
            reader.snapshot()
            shizukuInput.available.value = false
            input.typeText("hi", Target.OnElement("e1"), submit = false)
            assertEquals(listOf("tap AtPoint(x=20, y=30)", "type hi null"), a11yInput.calls)
        }

    @Test
    fun `launches use Shizuku only when background starts are blocked`() =
        runTest {
            val launched = mutableListOf<String>()

            fun controller(id: String) =
                object : AppController {
                    override val backendId = id
                    override val available = MutableStateFlow(true)

                    override suspend fun list(includeSystem: Boolean) = listOf(AppInfo("p", "P"))

                    override suspend fun launch(packageName: String) {
                        launched += "$id $packageName"
                    }

                    override suspend fun openUrl(url: String) {
                        launched += "$id $url"
                    }
                }
            val a11yOn = MutableStateFlow(true)
            val apps = ResolvingAppController(controller("pm"), controller("shizuku"), a11yOn)
            apps.launch("x")
            a11yOn.value = false
            apps.launch("y")
            assertEquals(listOf("pm x", "shizuku y"), launched)
        }

    @Test
    fun `the graph reports the active backend per capability`() =
        runTest {
            val graph =
                CapabilityGraph(
                    mapOf(
                        CapabilityId.READ_UI to listOf(a11yReader, shizukuReader),
                        CapabilityId.INJECT_INPUT to listOf(shizukuInput, a11yInput),
                    ),
                    backgroundScope,
                )
            shizukuInput.available.value = false
            a11yReader.available.value = false
            val state = graph.snapshot()
            assertEquals("shizuku", state.first { it.capability == CapabilityId.READ_UI }.active)
            assertEquals("accessibility", state.first { it.capability == CapabilityId.INJECT_INPUT }.active)
            a11yInput.available.value = false
            assertNull(graph.snapshot().first { it.capability == CapabilityId.INJECT_INPUT }.active)
        }
}
