package app.pocketpilot.core.automation

/** The element to tap, and the snapshot it belongs to, for [ui.tap]. */
data class RunRow(
    val snapshotId: String,
    val element: String,
)

/**
 * Finds the first run on a schedule list that starts inside a time window on the chosen days. Each run
 * row reads like `button desc="6:00 AM - 10:00 AM, GVA - Downtown"` and sits under its day's heading, such as
 * `text "Monday "`, in the order the screen lists them.
 */
object RunRows {
    private val SNAPSHOT = Regex("""^snapshot (\S+)""")
    private val DAY = Regex("""text "(Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday) *"""")
    private val ROW = Regex("""^\s*(e\d+) button desc="(\d{1,2}):(\d{2}) ([AP]M) - [^"]*".*\bclick\b""")
    private val DAY_CODES =
        mapOf(
            "mon" to "Monday",
            "tue" to "Tuesday",
            "wed" to "Wednesday",
            "thu" to "Thursday",
            "fri" to "Friday",
            "sat" to "Saturday",
            "sun" to "Sunday",
        )

    /** Full day names for codes such as "mon,fri" (case and spaces ignored); unknown codes are skipped. */
    fun dayNames(codes: String): Set<String> =
        codes
            .split(",")
            .mapNotNull { DAY_CODES[it.trim().lowercase()] }
            .toSet()

    /** The first run on one of [days] (full day names) starting from [from] to [to] (both HH:mm, inclusive), or null. */
    fun pick(
        snapshot: String,
        from: String,
        to: String,
        days: Set<String>,
    ): RunRow? {
        val earliest = minutesOf(from) ?: return null
        val latest = minutesOf(to) ?: return null
        val lines = snapshot.lines()
        val snapshotId = SNAPSHOT.find(lines.firstOrNull().orEmpty())?.groupValues?.get(1) ?: return null
        var day = ""
        for (line in lines.drop(1)) {
            val heading = DAY.find(line)
            val row = ROW.find(line)
            if (heading != null) {
                day = heading.groupValues[1]
            } else if (row != null && day in days) {
                val (element, hour, minute, half) = row.destructured
                val start = startMinutes(hour.toInt(), minute.toInt(), half)
                if (start in earliest..latest) return RunRow(snapshotId, element)
            }
        }
        return null
    }

    /** Minutes after midnight for "HH:mm", or null when it is not a time. */
    private fun minutesOf(time: String): Int? {
        val parts = time.split(":")
        if (parts.size != 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }

    /** Minutes after midnight for a 12-hour clock time, such as 12:30 AM (0:30) or 4:15 PM (16:15). */
    private fun startMinutes(
        hour: Int,
        minute: Int,
        half: String,
    ): Int = ((hour % 12) + if (half == "PM") 12 else 0) * 60 + minute
}
