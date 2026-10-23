package app.pocketpilot.core.automation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class VariableTypeTest {
    @Test
    fun `each type keeps its value in its own form`() {
        assertEquals("true", VariableType.BOOLEAN.normalize("On"))
        assertEquals("false", VariableType.BOOLEAN.normalize("no"))
        assertEquals("42", VariableType.INTEGER.normalize(" 42 "))
        assertEquals("2.5", VariableType.DECIMAL.normalize("2.5"))
        assertEquals("#FF8800", VariableType.COLOUR.normalize("#ff8800"))
        assertEquals("  keep me ", VariableType.STRING.normalize("  keep me "))
    }

    @Test
    fun `a value the type cannot hold is refused`() {
        assertFailsWith<AutomationFailure> { VariableType.BOOLEAN.normalize("maybe") }
        assertFailsWith<AutomationFailure> { VariableType.INTEGER.normalize("2.5") }
        assertFailsWith<AutomationFailure> { VariableType.DECIMAL.normalize("many") }
        assertFailsWith<AutomationFailure> { VariableType.COLOUR.normalize("red") }
    }

    @Test
    fun `dictionaries and arrays hold entries, and a label names its type`() {
        assertTrue(VariableType.DICTIONARY.holdsEntries)
        assertTrue(VariableType.ARRAY.holdsEntries)
        assertTrue(!VariableType.INTEGER.holdsEntries)
        assertEquals(VariableType.COLOUR, VariableType.named("colour"))
        assertEquals(VariableType.STRING, VariableType.named(null))
    }
}
