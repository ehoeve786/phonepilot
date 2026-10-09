package app.pocketpilot.core.tools

import app.pocketpilot.capability.api.DeviceInfo
import app.pocketpilot.core.audit.InMemoryAuditSink
import app.pocketpilot.core.model.PolicyProfile
import app.pocketpilot.core.orchestrator.CallDispatcher
import app.pocketpilot.core.orchestrator.SessionFactory
import app.pocketpilot.core.orchestrator.ToolRegistry
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceInfoToolTest {
    private val info =
        DeviceInfo(
            manufacturer = "Google",
            model = "Pixel 9",
            androidVersion = "16",
            sdkInt = 36,
            screen = DeviceInfo.Screen(1080, 2424, 420),
            battery = DeviceInfo.Battery(levelPercent = 81, charging = true),
            network = DeviceInfo.Network(connected = true, transport = "wifi"),
        )

    @Test
    fun `returns a summary and structured content`() =
        runTest {
            val dispatcher = CallDispatcher(ToolRegistry(setOf(DeviceInfoTool { info }), PolicyProfile.OSS), InMemoryAuditSink())
            val result = dispatcher.dispatch(SessionFactory().localTokenSession(), "device.info", JsonObject(emptyMap()))
            assertFalse(result.isError)
            val structured = result.structured!!
            assertEquals("Pixel 9", structured["model"]!!.jsonPrimitive.content)
            assertEquals(81, structured["battery"]!!.jsonObject["levelPercent"]!!.jsonPrimitive.int)
            assertTrue(result.content.toString().contains("Battery 81%, charging"))
        }
}
