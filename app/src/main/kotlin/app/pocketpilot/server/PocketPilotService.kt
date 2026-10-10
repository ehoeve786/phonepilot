package app.pocketpilot.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.pocketpilot.MainActivity
import app.pocketpilot.R
import app.pocketpilot.network.tailscale.TailscaleProvider
import app.pocketpilot.server.http.McpHttpServer
import app.pocketpilot.server.http.ServerState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps the MCP server and the Tailscale node running while the app is in the background (spec
 * section 12). Agent runs and the health monitor join it in later milestones.
 */
@AndroidEntryPoint
class PocketPilotService : Service() {
    @Inject lateinit var server: McpHttpServer

    @Inject lateinit var tailscale: TailscaleProvider

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent?.action == ACTION_STOP) {
            scope.launch {
                tailscale.pause()
                server.stop()
                stopSelf()
            }
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(getString(R.string.server_stopped)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        scope.launch {
            server.start()
            val state = server.serverState.value
            if (state is ServerState.Running) {
                tailscale.resume()
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, notification(getString(R.string.notification_text, state.url)))
            } else {
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        tailscale.pause()
        server.stop()
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val open =
            PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop =
            PendingIntent.getService(
                this,
                1,
                Intent(this, PocketPilotService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
        return Notification
            .Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.notification_stop), stop).build())
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "server"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "app.pocketpilot.action.STOP_SERVER"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, PocketPilotService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, PocketPilotService::class.java).setAction(ACTION_STOP))
        }
    }
}
