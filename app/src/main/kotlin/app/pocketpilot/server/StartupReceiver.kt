package app.pocketpilot.server

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts the server after a reboot or an update of PocketPilot; see [AutoStart]. */
class StartupReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action !in ACTIONS) return
        if (AutoStart.wasRunning(context)) PocketPilotService.start(context)
    }

    private companion object {
        val ACTIONS = setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)
    }
}
