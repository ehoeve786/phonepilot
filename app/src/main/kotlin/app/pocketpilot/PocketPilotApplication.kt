package app.pocketpilot

import android.app.Application
import app.pocketpilot.automation.AutomationRuntime
import app.pocketpilot.capability.shizuku.ShizukuConnection
import app.pocketpilot.diagnostics.CrashReports
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class PocketPilotApplication : Application() {
    @Inject lateinit var shizuku: ShizukuConnection

    @Inject lateinit var automations: dagger.Lazy<AutomationRuntime>

    val crashReports by lazy { CrashReports(this) }

    override fun onCreate() {
        crashReports.install()
        super.onCreate()
        // Shizuku hands over its binder only to a listener registered in this process, and the
        // privileged process created from this app must not start it again.
        if (!isPrivilegedProcess()) {
            shizuku.start()
            // Rules that are on start listening again whenever the process starts: after a reboot,
            // an update, an alarm or the owner opening the app.
            automations.get().init()
        }
    }

    private fun isPrivilegedProcess(): Boolean = getProcessName().endsWith(":privileged")
}
