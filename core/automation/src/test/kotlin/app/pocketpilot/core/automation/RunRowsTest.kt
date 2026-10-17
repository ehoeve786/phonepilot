package app.pocketpilot.core.automation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Picks open runs by start time on weekdays, from a schedule snapshot shaped like Courier's Open Runs list. */
class RunRowsTest {
    private val weekdays = RunRows.dayNames("mon,tue,wed,thu,fri")

    private fun snapshot(day: String) =
        """
        snapshot a11 com.delco.courier 1080x2316
        e5 window "Courier" [0,0,1080,2316]
          e35 listitem [0,426,1080,2178]
            e36 text "Saturday " [56,466,247,527]
            e38 button desc="4:15 PM - 7:45 PM, GVA - Downtown" [0,567,1080,848] click
            e41 text "$day " [56,888,214,949]
            e43 button desc="1:30 AM - 5:00 AM, GVA - Downtown" [0,989,1080,1271] click
            e46 button desc="2:00 AM - 5:00 AM, GVA - Downtown" [0,1275,1080,1557] click
            e49 button desc="6:00 AM - 10:00 AM, GVA - Downtown" [0,1562,1080,1843] click
            e52 text "Monday " [56,1884,228,1945]
            e54 text "No Runs Available" [56,1984,1080,2035]
        """.trimIndent()

    @Test
    fun `weekend runs are never picked`() {
        assertNull(RunRows.pick(snapshot("Sunday"), "06:00", "15:00", weekdays))
    }

    @Test
    fun `a weekday run starting inside the window is picked by its row`() {
        assertEquals(RunRow("a11", "e49"), RunRows.pick(snapshot("Monday"), "06:00", "15:00", weekdays))
    }

    @Test
    fun `runs starting outside the window are skipped`() {
        val early = snapshot("Monday").replace("6:00 AM - 10:00 AM", "5:59 AM - 10:00 AM")
        assertNull(RunRows.pick(early, "06:00", "15:00", weekdays))
    }

    @Test
    fun `afternoon starts count from noon, and the window end is included`() {
        val afternoon = snapshot("Friday").replace("6:00 AM - 10:00 AM", "3:00 PM - 7:00 PM")
        assertEquals(RunRow("a11", "e49"), RunRows.pick(afternoon, "06:00", "15:00", weekdays))
        assertNull(RunRows.pick(afternoon, "06:00", "14:59", weekdays))
    }

    @Test
    fun `a row without a clickable button is not picked`() {
        val noClick =
            snapshot(
                "Monday",
            ).replace("e49 button desc=\"6:00 AM - 10:00 AM, GVA - Downtown\" [0,1562,1080,1843] click", "e49 text \"6:00 AM - 10:00 AM\"")
        assertNull(RunRows.pick(noClick, "06:00", "15:00", weekdays))
    }

    @Test
    fun `the days setting chooses which days count, including Sunday`() {
        assertEquals(RunRow("a11", "e49"), RunRows.pick(snapshot("Sunday"), "06:00", "15:00", RunRows.dayNames("sun")))
        assertNull(RunRows.pick(snapshot("Sunday"), "06:00", "15:00", RunRows.dayNames("mon, fri")))
    }

    @Test
    fun `day codes are read in any case and unknown codes are skipped`() {
        assertEquals(setOf("Monday", "Friday"), RunRows.dayNames("MON, fri, xyz"))
        assertEquals(emptySet(), RunRows.dayNames(""))
    }
}
