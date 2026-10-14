package app.pocketpilot.automation

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
import app.pocketpilot.core.designsystem.R as DsR

/**
 * Keeps PocketPilot's process alive while any automation is on, so its triggers keep listening with
 * the app closed. [AutomationRuntime] starts and stops it.
 */
class AutomationService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(this, intent?.getIntExtra(EXTRA_COUNT, 0) ?: 0),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        return START_STICKY
    }

    companion object {
        private const val CHANNEL_ID = "automations"
        private const val NOTIFICATION_ID = 3
        private const val EXTRA_COUNT = "count"

        @Volatile private var running = false

        /** Starting can be refused while the app is in the background; the triggers still work while the process lives. */
        fun start(context: Context) {
            running =
                runCatching {
                    ContextCompat.startForegroundService(context, Intent(context, AutomationService::class.java))
                }.isSuccess
        }

        fun stop(context: Context) {
            running = false
            context.stopService(Intent(context, AutomationService::class.java))
        }

        /** Shows how many rules are on. */
        fun update(
            context: Context,
            count: Int,
        ) {
            if (!running) return
            runCatching { context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(context, count)) }
        }

        private fun notification(
            context: Context,
            count: Int,
        ): Notification {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.automation_channel), NotificationManager.IMPORTANCE_MIN),
            )
            val open =
                PendingIntent.getActivity(
                    context,
                    2,
                    Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_AUTOMATIONS),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            return Notification
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(DsR.drawable.core_designsystem_ic_bolt)
                .setContentTitle(context.getString(R.string.automation_service_title))
                .apply {
                    if (count > 0) setContentText(context.resources.getQuantityString(R.plurals.automation_service_text, count, count))
                }.setContentIntent(open)
                .setOngoing(true)
                .build()
        }
    }
}
