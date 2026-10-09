package app.pocketpilot.capability.shizuku

/** Finds the resumed activity in `dumpsys activity activities` output. */
internal object ForegroundParser {
    // Lines look like: topResumedActivity=ActivityRecord{1a2b3c u0 com.android.settings/.Settings t42}
    private val RESUMED = Regex("""(?:topResumedActivity|mResumedActivity|ResumedActivity)[:=]\s*ActivityRecord\{\S+ u\d+ (\S+/\S+)""")

    /** "package/full.ActivityClass", or null when nothing is resumed. */
    fun parse(dumpsys: String): String? {
        val component = RESUMED.find(dumpsys)?.groupValues?.get(1) ?: return null
        val packageName = component.substringBefore('/')
        val activity = component.substringAfter('/')
        return "$packageName/${if (activity.startsWith('.')) packageName + activity else activity}"
    }
}
