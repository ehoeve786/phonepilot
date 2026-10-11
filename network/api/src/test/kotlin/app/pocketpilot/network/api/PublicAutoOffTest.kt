package app.pocketpilot.network.api

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PublicAutoOffTest {
    private val hour = 60 * 60_000L

    @Test
    fun turnsOffAnHourAfterItWasTurnedOnWithoutRequests() {
        val autoOff = PublicAutoOff()
        autoOff.restart(nowMs = 1_000)
        assertFalse(autoOff.expired(nowMs = 1_000 + hour - 1, lastRequestMs = 0))
        assertTrue(autoOff.expired(nowMs = 1_000 + hour, lastRequestMs = 0))
    }

    @Test
    fun eachRequestStartsTheClockAgain() {
        val autoOff = PublicAutoOff()
        autoOff.restart(nowMs = 0)
        assertFalse(autoOff.expired(nowMs = hour + 5, lastRequestMs = 10))
        assertTrue(autoOff.expired(nowMs = hour + 10, lastRequestMs = 10))
    }

    @Test
    fun aRequestFromBeforeTheRestartDoesNotCount() {
        val autoOff = PublicAutoOff(minutes = 1)
        autoOff.restart(nowMs = 120_000)
        assertFalse(autoOff.expired(nowMs = 150_000, lastRequestMs = 10_000))
    }

    @Test
    fun zeroMinutesNeverTurnsOff() {
        val autoOff = PublicAutoOff(minutes = 0)
        assertFalse(autoOff.expired(nowMs = 100 * hour, lastRequestMs = 0))
    }
}
