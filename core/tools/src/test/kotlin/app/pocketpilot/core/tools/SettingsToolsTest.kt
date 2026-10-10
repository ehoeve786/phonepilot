package app.pocketpilot.core.tools

import app.pocketpilot.capability.api.settings.SettingKey
import app.pocketpilot.capability.api.settings.SettingsController
import app.pocketpilot.core.audit.InMemoryAuditSink
import app.pocketpilot.core.model.PolicyProfile
import app.pocketpilot.core.model.ToolErrorCode
import app.pocketpilot.core.orchestrator.CallDispatcher
import app.pocketpilot.core.orchestrator.SessionFactory
import app.pocketpilot.core.orchestrator.ToolRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsToolsTest {
    private class FakeSettings : SettingsController {
        override val backendId = "fake"
        override val available = MutableStateFlow(true)
        val values = mutableMapOf(SettingKey.DARK_MODE to "on")

        override suspend fun get(key: SettingKey) = values[key] ?: "off"

        override suspend fun set(
            key: SettingKey,
            value: String,
        ) {
            values[key] = value
        }
    }

    private val settings = FakeSettings()
    private val dispatcher =
        CallDispatcher(ToolRegistry(setOf(SettingsGetTool(settings), SettingsSetTool(settings)), PolicyProfile.OSS), InMemoryAuditSink())
    private val session = SessionFactory().localTokenSession()

    @Test
    fun `sets a setting in canonical form and reads it back`() =
        runTest {
            val result =
                dispatcher.dispatch(
                    session,
                    "settings.set",
                    buildJsonObject {
                        put("key", "dark_mode")
                        put("value", " OFF ")
                    },
                )
            assertFalse(result.isError)
            assertTrue(result.content.toString().contains("dark_mode is now off"))
            assertEquals("off", settings.values[SettingKey.DARK_MODE])
        }

    @Test
    fun `rejects values the setting does not take`() =
        runTest {
            val result =
                dispatcher.dispatch(
                    session,
                    "settings.set",
                    buildJsonObject {
                        put("key", "volume")
                        put("value", "loud")
                    },
                )
            assertEquals(ToolErrorCode.INVALID_ARGUMENTS, result.errorCode)
        }

    @Test
    fun `reads a setting`() =
        runTest {
            val result = dispatcher.dispatch(session, "settings.get", buildJsonObject { put("key", "dark_mode") })
            assertTrue(result.content.toString().contains("dark_mode: on"))
        }
}
