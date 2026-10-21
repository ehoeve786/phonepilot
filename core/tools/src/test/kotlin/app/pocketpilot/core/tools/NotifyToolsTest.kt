package app.pocketpilot.core.tools

import app.pocketpilot.capability.api.notifications.NotificationAction
import app.pocketpilot.capability.api.notifications.NotificationCommand
import app.pocketpilot.capability.api.notifications.NotificationSource
import app.pocketpilot.capability.api.notifications.PhoneNotification
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

class NotifyToolsTest {
    private class FakeNotifications : NotificationSource {
        override val backendId = "fake"
        override val available = MutableStateFlow(true)
        val acted = mutableListOf<Pair<String, NotificationCommand>>()
        var shade =
            listOf(
                PhoneNotification(
                    key = "msg",
                    packageName = "com.google.android.apps.messaging",
                    title = "Anna",
                    text = "Dinner at 7?",
                    postedAtMillis = 2,
                    clearable = true,
                    opens = true,
                    actions =
                        listOf(
                            NotificationAction("Reply", acceptsText = true),
                            NotificationAction("Mark as read", acceptsText = false),
                        ),
                ),
                PhoneNotification("otp", "com.td", "TD", "Your code is 123456", 1, true, true, emptyList()),
            )

        override suspend fun active() = shade

        override suspend fun act(
            key: String,
            command: NotificationCommand,
        ) {
            acted += key to command
        }
    }

    private val source = FakeNotifications()
    private val hidden = { pkg: String -> pkg == "com.td" }
    private val dispatcher =
        CallDispatcher(
            ToolRegistry(setOf(NotifyListTool(source, hidden), NotifyActTool(source, hidden)), PolicyProfile.OSS),
            InMemoryAuditSink(),
        )
    private val session = SessionFactory().localTokenSession()

    @Test
    fun `lists notifications but withholds denied apps`() =
        runTest {
            val text = dispatcher.dispatch(session, "notify.list", buildJsonObject {}).content.toString()
            assertTrue("Dinner at 7?" in text)
            assertFalse("123456" in text, "a bank's code never leaves the phone")
            assertTrue("\"withheld\":1" in text)
        }

    @Test
    fun `lists the full payload of a notification`() =
        runTest {
            val withExtras =
                PhoneNotification(
                    key = "offer",
                    packageName = "com.delco.courier",
                    title = "SkipTheDishes",
                    text = "You've been matched to a new order.",
                    postedAtMillis = 3,
                    clearable = true,
                    opens = true,
                    actions = emptyList(),
                    extras = mapOf("android.bigText" to "Pickup at 5th and Main", "android.subText" to "Order 42"),
                )
            source.shade = listOf(withExtras)
            val text = dispatcher.dispatch(session, "notify.list", buildJsonObject {}).content.toString()
            assertTrue("Pickup at 5th and Main" in text)
            assertTrue("android.subText" in text)
        }

    @Test
    fun `replies through a button that takes text`() =
        runTest {
            val result =
                dispatcher.dispatch(
                    session,
                    "notify.act",
                    buildJsonObject {
                        put("key", "msg")
                        put("action", "reply")
                        put("text", "Sounds good")
                    },
                )
            assertFalse(result.isError)
            assertEquals("msg" to NotificationCommand.Action("reply", "Sounds good"), source.acted.single())
        }

    @Test
    fun `refuses unknown buttons and denied apps`() =
        runTest {
            val noButton =
                dispatcher.dispatch(
                    session,
                    "notify.act",
                    buildJsonObject {
                        put("key", "msg")
                        put("action", "Delete")
                    },
                )
            assertEquals(ToolErrorCode.INVALID_ARGUMENTS, noButton.errorCode)
            val bank =
                dispatcher.dispatch(
                    session,
                    "notify.act",
                    buildJsonObject {
                        put("key", "otp")
                        put("action", "open")
                    },
                )
            assertEquals(ToolErrorCode.ELEMENT_NOT_FOUND, bank.errorCode)
            assertTrue(source.acted.isEmpty())
        }
}
