package app.pocketpilot.core.automation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SafeRegexTest {
    @Test
    fun `ordinary patterns match as before`() {
        assertEquals("42", SafeRegex.find("(\\d+)", "order 42 now")?.value)
        assertTrue(SafeRegex.matches("door", "front door open"))
    }

    @Test
    fun `a search that reads more text than the budget is stopped`() {
        // A character class is tried at every position, so this reads the whole text.
        assertFailsWith<SafeRegex.TooSlow> { SafeRegex.matches("[z]", "a".repeat(5_000_000)) }
    }
}
