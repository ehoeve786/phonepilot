package app.pocketpilot.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SessionIdTest {
    @Test
    fun `accepts a ulid`() {
        assertEquals("01J9ZQ3K8M4X7T2B5N6P8R9S0V", SessionId("01J9ZQ3K8M4X7T2B5N6P8R9S0V").value)
    }

    @Test
    fun `rejects anything else`() {
        assertFailsWith<IllegalArgumentException> { SessionId("not-a-ulid") }
        assertFailsWith<IllegalArgumentException> { SessionId("01J9ZQ3K8M4X7T2B5N6P8R9S0U") }
    }
}
