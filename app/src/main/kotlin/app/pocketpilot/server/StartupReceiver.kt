package app.pocketpilot.server

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.pocketpilot.automation.automations

/** Restarts the server after a reboot or an update of PocketPilot (see [AutoStart]) and fires "Phone started" rules. */
class StartupReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action !in ACTIONS) return
        if (AutoStart.wasRunning(context)) PocketPilotService.start(context)
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) context.automations().onBoot()
    }

    private companion object {
        val ACTIONS = setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)
    }
}
