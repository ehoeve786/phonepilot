package app.pocketpilot.core.audit

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RedactorTest {
    private val redactor = Redactor()

    @Test
    fun `sensitive values are replaced by a summary`() {
        val redacted =
            redactor.redact(
                buildJsonObject {
                    put("text", "my secret message")
                    put("element", "e12")
                },
            )
        assertTrue(Redactor.isRedacted(redacted.getValue("text")))
        assertFalse(redacted.toString().contains("my secret message"))
        assertEquals(JsonPrimitive("e12"), redacted["element"])
    }

    @Test
    fun `nested objects are redacted too`() {
        val redacted = redactor.redact(buildJsonObject { putJsonObject("extras") { put("password", "hunter2") } })
        assertTrue(Redactor.isRedacted(redacted.getValue("extras").jsonObject.getValue("password")))
    }

    @Test
    fun `in-memory sink keeps the newest events up to capacity`() =
        runBlocking {
            val sink = InMemoryAuditSink(capacity = 2)
            repeat(3) { sink.record(event("e$it")) }
            assertEquals(listOf("e2", "e1"), sink.recent.value.map { it.id })
        }

    private fun event(id: String) =
        AuditEvent(
            id = id,
            timestampMillis = 0,
            sessionId = "s",
            principal = "local",
            tool = "device.info",
            argumentsRedacted = buildJsonObject {},
            decision = AuditDecision.ALLOW,
            backend = null,
            durationMs = 1,
            outcome = AuditOutcome.SUCCESS,
            errorCode = null,
        )
}
