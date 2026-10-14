package app.pocketpilot.server

import android.content.Context

/**
 * Brings the server back after the phone restarts or PocketPilot is updated, if it was running
 * before; pressing Stop keeps it stopped. The remote networks that were on come back with it.
 */
object AutoStart {
    private const val PREFS = "startup"
    private const val KEY_RUNNING = "running"

    /** Remembers whether the server is running, so a restart knows whether to bring it back. */
    fun setRunning(
        context: Context,
        running: Boolean,
    ) {
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_RUNNING, running)
            .apply()
    }

    fun wasRunning(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_RUNNING, false)
}
