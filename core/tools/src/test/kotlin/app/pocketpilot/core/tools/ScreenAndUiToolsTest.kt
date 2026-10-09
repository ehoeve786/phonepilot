package app.pocketpilot.core.tools

import app.pocketpilot.capability.api.screen.Frame
import app.pocketpilot.capability.api.screen.ScreenCapturer
import app.pocketpilot.core.audit.InMemoryAuditSink
import app.pocketpilot.core.imaging.ImagePipeline
import app.pocketpilot.core.model.ContentPart
import app.pocketpilot.core.model.PolicyProfile
import app.pocketpilot.core.model.ToolErrorCode
import app.pocketpilot.core.model.ToolResult
import app.pocketpilot.core.orchestrator.CallDispatcher
import app.pocketpilot.core.orchestrator.SessionFactory
import app.pocketpilot.core.orchestrator.ToolHandler
import app.pocketpilot.core.orchestrator.ToolRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScreenAndUiToolsTest {
    private val reader = FakeReader()
    private val input = FakeInput()
    private val apps = FakeApps(reader)
    private var encodedFrame: Frame? = null
    private val capturer =
        object : ScreenCapturer {
            override val available = MutableStateFlow(true)

            override suspend fun capture() = Frame(1080, 2400, IntArray(1080 * 2400) { -1 })
        }
    private val pipeline =
        ImagePipeline { frame, _, _, _, _ ->
            encodedFrame = frame
            byteArrayOf(7)
        }

    private val handlers: Set<ToolHandler> =
        setOf(
            ScreenSnapshotTool(reader),
            ScreenFindTool(reader),
            ScreenWaitForTool(reader),
            ScreenWaitForChangeTool(reader),
            ScreenCaptureTool(capturer, pipeline, reader),
            UiTapTool(input, reader),
            UiLongPressTool(input, reader),
            UiSwipeTool(input, reader),
            UiTypeTextTool(input, reader),
            UiPressKeyTool(input, reader),
            UiGlobalActionTool(input, reader),
            AppListTool(apps),
            AppCurrentTool(reader),
            AppLaunchTool(apps, reader),
            AppOpenUrlTool(apps, reader),
        )
    private val dispatcher = CallDispatcher(ToolRegistry(handlers, PolicyProfile.OSS), InMemoryAuditSink())

    private suspend fun call(
        tool: String,
        arguments: String = "{}",
    ): ToolResult = dispatcher.dispatch(SessionFactory().localTokenSession(), tool, Json.parseToJsonElement(arguments).jsonObject)

    private fun ToolResult.text() = content.filterIsInstance<ContentPart.Text>().joinToString("\n") { it.text }

    @Test
    fun `every M3 tool registers with a valid schema`() {
        assertEquals(15, ToolRegistry(handlers, PolicyProfile.PLAY).specs.size)
    }

    @Test
    fun `snapshot is compact JSON without nulls`() =
        runTest {
            val text = call("screen.snapshot").text()
            assertTrue(text.startsWith("""{"snapshotId":"s1","package":"com.android.settings""""), text)
            assertFalse(text.contains("null"))
            assertTrue(text.contains(""""bounds":[40,210,400,260]"""))
        }

    @Test
    fun `find matches text and resource ID suffix`() =
        runTest {
            assertTrue(call("screen.find", """{"text":"display"}""").text().contains(""""id":"e3""""))
            assertTrue(call("screen.find", """{"resourceId":"title"}""").text().contains(""""id":"e3""""))
            assertEquals(ToolErrorCode.ELEMENT_NOT_FOUND, call("screen.find", """{"text":"Wi-Fi"}""").errorCode)
            assertEquals(ToolErrorCode.INVALID_ARGUMENTS, call("screen.find").errorCode)
        }

    @Test
    fun `tap takes an element or a point and returns the settled screen`() =
        runTest {
            val result = call("ui.tap", """{"element":"e3"}""")
            assertFalse(result.isError, result.text())
            assertEquals("tap OnElement(elementId=e3, snapshotId=null)", input.calls.single())
            assertTrue(result.text().contains("Screen now:"))

            call("ui.tap", """{"x":10,"y":20,"returnSnapshot":false}""").also { assertFalse(it.text().contains("Screen now")) }
            assertEquals(ToolErrorCode.INVALID_ARGUMENTS, call("ui.tap", """{"x":10}""").errorCode)
            assertEquals(ToolErrorCode.INVALID_ARGUMENTS, call("ui.tap", """{"element":"e3","x":1,"y":2}""").errorCode)
        }

    @Test
    fun `swipe needs four coordinates or an element and direction`() =
        runTest {
            assertFalse(call("ui.swipe", """{"fromX":1,"fromY":2,"toX":3,"toY":4}""").isError)
            assertFalse(call("ui.swipe", """{"element":"e2","direction":"up"}""").isError)
            assertEquals(ToolErrorCode.INVALID_ARGUMENTS, call("ui.swipe", """{"element":"e2"}""").errorCode)
            assertEquals(listOf("swipe 1,2 3,4 300", "swipeOn e2 UP"), input.calls)
        }

    @Test
    fun `type text, keys and global actions reach the input controller`() =
        runTest {
            call("ui.type_text", """{"text":"dark","submit":true}""")
            call("ui.press_key", """{"key":"BACK"}""")
            call("ui.global_action", """{"action":"quick_settings"}""")
            assertEquals(listOf("type dark null true", "key BACK", "global QUICK_SETTINGS"), input.calls)
            assertEquals(ToolErrorCode.INVALID_ARGUMENTS, call("ui.press_key", """{"key":"VOLUME_UP"}""").errorCode)
        }

    @Test
    fun `launch resolves a label and waits for the app`() =
        runTest {
            val result = call("app.launch", """{"label":"settings"}""")
            assertFalse(result.isError, result.text())
            assertEquals(listOf("com.android.settings"), apps.launched)
            assertEquals(ToolErrorCode.ELEMENT_NOT_FOUND, call("app.launch", """{"label":"Maps"}""").errorCode)
            assertTrue(call("app.launch", """{"label":"Note"}""").text().contains("Several apps match"))
            assertFalse(call("app.launch", """{"label":"notes"}""").isError, "an exact label wins over partial matches")
        }

    @Test
    fun `list filters and open_url refuses other schemes`() =
        runTest {
            assertEquals(
                """[{"package":"com.example.notes","label":"Notes"},{"package":"com.example.notes.pro","label":"Notes Pro"}]""",
                call("app.list", """{"filter":"notes"}""").text(),
            )
            assertFalse(call("app.list", """{"includeSystem":false}""").text().contains("settings"))
            assertEquals(ToolErrorCode.INVALID_ARGUMENTS, call("app.open_url", """{"url":"intent://x#Intent;end"}""").errorCode)
            assertFalse(call("app.open_url", """{"url":"https://example.com"}""").isError)
        }

    @Test
    fun `capture returns an image and the transform and blacks out password fields`() =
        runTest {
            val result = call("screen.capture", """{"maxEdge":1200}""")
            assertFalse(result.isError, result.text())
            val image = result.content.filterIsInstance<ContentPart.Image>().single()
            assertEquals("image/webp", image.mimeType)
            val info = Json.parseToJsonElement(result.text()).jsonObject
            assertEquals("540", info["imageWidth"].toString())
            assertEquals("2.0", (info["transform"] as JsonObject)["scaleX"].toString())
            assertEquals(0xFF000000.toInt(), encodedFrame!!.pixels[450 * 1080 + 500], "password field is redacted")
        }
}
