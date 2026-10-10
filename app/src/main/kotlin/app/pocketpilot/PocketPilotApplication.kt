package app.pocketpilot

import android.app.Application
import app.pocketpilot.capability.shizuku.ShizukuConnection
import app.pocketpilot.diagnostics.CrashReports
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class PocketPilotApplication : Application() {
    @Inject lateinit var shizuku: ShizukuConnection

    val crashReports by lazy { CrashReports(this) }

    override fun onCreate() {
        crashReports.install()
        super.onCreate()
        // Shizuku hands over its binder only to a listener registered in this process, and the
        // privileged process created from this app must not start it again.
        if (!isPrivilegedProcess()) shizuku.start()
    }

    private fun isPrivilegedProcess(): Boolean = getProcessName().endsWith(":privileged")
}
