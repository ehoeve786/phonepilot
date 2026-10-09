package app.pocketpilot.core.common

import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UlidGeneratorTest {
    private val pattern = Regex("^[0-7][0-9A-HJKMNP-TV-Z]{25}$")

    @Test
    fun `produces 26 crockford characters`() {
        val ulid = UlidGenerator().next()
        assertTrue(pattern.matches(ulid), ulid)
    }

    @Test
    fun `encodes the timestamp so it can be read back`() {
        val millis = 1_791_577_766_036L
        val ulid = UlidGenerator(clock = { millis }, random = Random(1)).next()
        assertEquals(millis, UlidGenerator.timestampOf(ulid))
    }

    @Test
    fun `later ulids sort after earlier ones`() {
        var now = 1_000L
        val generator = UlidGenerator(clock = { now })
        val first = generator.next()
        now += 1
        val second = generator.next()
        assertTrue(first < second)
    }
}
