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
    fun `a pattern that backtracks for ages is stopped`() {
        assertFailsWith<SafeRegex.TooSlow> { SafeRegex.matches("(a+)+$", "a".repeat(40) + "!") }
    }
}
