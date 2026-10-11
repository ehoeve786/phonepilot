package app.pocketpilot.control

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.pocketpilot.R
import app.pocketpilot.agent.AgentCoordinator
import app.pocketpilot.capability.accessibility.A11yOverlay
import app.pocketpilot.core.model.Session
import app.pocketpilot.core.model.SessionKind
import app.pocketpilot.core.orchestrator.SessionFactory
import app.pocketpilot.core.policy.ControlIndicator
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Tells the owner who is acting on the phone and lets them stop it (spec section 8). While a client or
 * agent run acts, a pill at the top of the screen says so, with a Stop button; it goes away
 * [HIDE_AFTER_MS] after the last action. Without the Accessibility service, a notification stands in.
 *
 * Stop ends an agent run. When an MCP client was acting, it also refuses every MCP client call until
 * the owner allows them again in the app; that stop survives restarts.
 */
class ControlOverlay(
    private val context: Context,
    private val scope: CoroutineScope,
    private val agent: dagger.Lazy<AgentCoordinator>,
) : ControlIndicator {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val stopped = MutableStateFlow(prefs.getBoolean(KEY_STOPPED, false))

    /** True while the owner has stopped AI apps from using the phone. */
    val clientsStopped: StateFlow<Boolean> = stopped.asStateFlow()

    private var hideJob: Job? = null

    @Volatile private var lastActing: SessionKind? = null

    @Synchronized
    override fun acting(session: Session) {
        lastActing = session.kind
        val text = context.getString(R.string.control_overlay_text, who(session))
        val stopLabel = context.getString(R.string.control_stop)
        if (!A11yOverlay.show(text, stopLabel, ::stop)) notify(text, stopLabel)
        hideJob?.cancel()
        hideJob =
            scope.launch {
                delay(HIDE_AFTER_MS)
                hide()
            }
    }

    /** The overlay's Stop button: ends the agent run, and refuses AI apps until [allowAgain] if one was acting. */
    fun stop() {
        if (lastActing != SessionKind.AGENT_RUN) {
            prefs.edit().putBoolean(KEY_STOPPED, true).apply()
            stopped.value = true
        }
        runCatching { agent.get().stop() }
        hide()
    }

    fun allowAgain() {
        prefs.edit().putBoolean(KEY_STOPPED, false).apply()
        stopped.value = false
    }

    private fun hide() {
        A11yOverlay.hide()
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    private fun who(session: Session): String =
        when {
            session.kind == SessionKind.AGENT_RUN -> context.getString(R.string.control_who_agent)
            session.principal == SessionFactory.LOCAL_PRINCIPAL -> context.getString(R.string.control_who_local)
            else -> session.principal
        }

    private fun notify(
        text: String,
        stopLabel: String,
    ) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.control_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val stopIntent =
            PendingIntent.getBroadcast(
                context,
                0,
                Intent(context, ControlStopReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val notification =
            Notification
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(Notification.Action.Builder(null, stopLabel, stopIntent).build())
                .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    private companion object {
        const val PREFS = "control"
        const val KEY_STOPPED = "clients_stopped"
        const val CHANNEL_ID = "control"
        const val NOTIFICATION_ID = 4
        const val HIDE_AFTER_MS = 10_000L
    }
}

/** The fallback notification's Stop button. */
@AndroidEntryPoint
class ControlStopReceiver : BroadcastReceiver() {
    @Inject lateinit var overlay: ControlOverlay

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        overlay.stop()
    }
}
