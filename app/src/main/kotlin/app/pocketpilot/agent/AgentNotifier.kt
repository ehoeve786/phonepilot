package app.pocketpilot.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.pocketpilot.MainActivity
import app.pocketpilot.R
import app.pocketpilot.agent.runtime.RunState
import app.pocketpilot.agent.runtime.RunStatus
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * The ongoing "agent is in control" notification (spec section 9, Takeover): what the run is doing,
 * with Take over or Resume, and Stop, reachable from any app.
 */
class AgentNotifier(
    private val context: Context,
) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun update(state: RunState?) {
        if (state == null || !state.active) {
            manager.cancel(NOTIFICATION_ID)
            return
        }
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.agent_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val paused = state.status == RunStatus.PAUSED
        val last =
            state.steps
                .lastOrNull { it.calls.isNotEmpty() }
                ?.calls
                ?.lastOrNull()
                ?.tool
        val text =
            if (paused) {
                context.getString(R.string.agent_notification_paused)
            } else {
                context.getString(R.string.agent_notification_step, state.steps.size, last ?: "…")
            }
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification =
            Notification
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setContentTitle(context.getString(R.string.agent_notification_title, state.goal))
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(
                    action(
                        if (paused) ACTION_RESUME else ACTION_PAUSE,
                        if (paused) R.string.agent_resume else R.string.agent_take_over,
                    ),
                ).addAction(action(ACTION_STOP, R.string.agent_stop))
                .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun action(
        name: String,
        label: Int,
    ): Notification.Action {
        val intent =
            PendingIntent.getBroadcast(
                context,
                name.hashCode(),
                Intent(context, AgentControlReceiver::class.java).setAction(name),
                PendingIntent.FLAG_IMMUTABLE,
            )
        return Notification.Action.Builder(null, context.getString(label), intent).build()
    }

    companion object {
        private const val CHANNEL_ID = "agent"
        private const val NOTIFICATION_ID = 2
        const val ACTION_PAUSE = "app.pocketpilot.action.AGENT_PAUSE"
        const val ACTION_RESUME = "app.pocketpilot.action.AGENT_RESUME"
        const val ACTION_STOP = "app.pocketpilot.action.AGENT_STOP"
    }
}

/** Handles the notification's buttons. */
@AndroidEntryPoint
class AgentControlReceiver : BroadcastReceiver() {
    @Inject lateinit var agent: AgentCoordinator

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        when (intent.action) {
            AgentNotifier.ACTION_PAUSE -> agent.pause()
            AgentNotifier.ACTION_RESUME -> agent.resume()
            AgentNotifier.ACTION_STOP -> agent.stop()
        }
    }
}
