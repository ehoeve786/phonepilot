package app.pocketpilot

import android.app.Application
import app.pocketpilot.capability.shizuku.ShizukuConnection
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class PocketPilotApplication : Application() {
    @Inject lateinit var shizuku: ShizukuConnection

    override fun onCreate() {
        super.onCreate()
        // Shizuku hands over its binder only to a listener registered in this process, and the
        // privileged process created from this app must not start it again.
        if (!isPrivilegedProcess()) shizuku.start()
    }

    private fun isPrivilegedProcess(): Boolean = getProcessName().endsWith(":privileged")
}
