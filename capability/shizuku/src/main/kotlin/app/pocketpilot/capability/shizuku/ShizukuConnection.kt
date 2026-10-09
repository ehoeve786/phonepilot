package app.pocketpilot.capability.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import app.pocketpilot.core.model.ToolErrorCode
import app.pocketpilot.core.model.ToolException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import rikka.shizuku.Shizuku

/** Where PocketPilot stands with Shizuku, shown in Doctor. */
enum class ShizukuState {
    /** Shizuku is not installed or not started. */
    NOT_RUNNING,

    /** Shizuku is older than version 11, which has no user services. */
    UNSUPPORTED,

    /** Shizuku runs, but the owner has not allowed PocketPilot yet. */
    PERMISSION_NEEDED,

    /** Starting the privileged service. */
    CONNECTING,
    CONNECTED,
}

/**
 * Follows Shizuku's binder and permission, and keeps PocketPilot's [PrivilegedService] bound while
 * both are there. Shizuku restarts the service when [PrivilegedService.VERSION] changes.
 */
class ShizukuConnection(
    private val context: Context,
) {
    private val mutableState = MutableStateFlow(ShizukuState.NOT_RUNNING)
    val state: StateFlow<ShizukuState> = mutableState.asStateFlow()

    @Volatile
    private var service: IPocketPilotPrivileged? = null

    private val args =
        Shizuku
            .UserServiceArgs(ComponentName(context.packageName, PrivilegedService::class.java.name))
            .daemon(false)
            .processNameSuffix("privileged")
            .tag("pocketpilot-privileged")
            .version(PrivilegedService.VERSION)

    private val connection =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName,
                binder: IBinder?,
            ) {
                if (binder == null || !binder.pingBinder()) return
                service = IPocketPilotPrivileged.Stub.asInterface(binder)
                mutableState.value = ShizukuState.CONNECTED
            }

            override fun onServiceDisconnected(name: ComponentName) {
                service = null
                refresh()
            }
        }

    private var started = false

    /** Starts following Shizuku. Call once, from the main thread. */
    fun start() {
        if (started) return
        started = true
        Shizuku.addBinderReceivedListenerSticky { refresh() }
        Shizuku.addBinderDeadListener {
            service = null
            mutableState.value = ShizukuState.NOT_RUNNING
        }
        Shizuku.addRequestPermissionResultListener { _, _ -> refresh() }
    }

    /** Shows Shizuku's permission dialog. */
    fun requestPermission() {
        if (Shizuku.pingBinder() && !Shizuku.isPreV11()) Shizuku.requestPermission(PERMISSION_REQUEST)
    }

    /** The bound privileged service, or CAPABILITY_UNAVAILABLE saying what to do. */
    fun service(): IPocketPilotPrivileged =
        service ?: throw ToolException(
            ToolErrorCode.CAPABILITY_UNAVAILABLE,
            "Shizuku is not connected (${state.value.name.lowercase().replace('_', ' ')})",
            "Ask the phone's owner to start Shizuku and allow PocketPilot, or to turn on PocketPilot in Accessibility settings",
        )

    /** True while the privileged service is bound. */
    fun connectedFlow() = state.map { it == ShizukuState.CONNECTED }

    private fun refresh() {
        mutableState.value =
            when {
                !Shizuku.pingBinder() -> {
                    ShizukuState.NOT_RUNNING
                }

                Shizuku.isPreV11() -> {
                    ShizukuState.UNSUPPORTED
                }

                Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> {
                    ShizukuState.PERMISSION_NEEDED
                }

                service != null -> {
                    ShizukuState.CONNECTED
                }

                else -> {
                    Shizuku.bindUserService(args, connection)
                    ShizukuState.CONNECTING
                }
            }
    }

    private companion object {
        const val PERMISSION_REQUEST = 4_201
    }
}
