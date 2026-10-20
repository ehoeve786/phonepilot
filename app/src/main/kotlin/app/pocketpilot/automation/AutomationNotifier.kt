package app.pocketpilot.automation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import app.pocketpilot.MainActivity
import app.pocketpilot.R
import app.pocketpilot.capability.api.notifications.NotificationPoster
import app.pocketpilot.core.automation.Rule
import app.pocketpilot.core.designsystem.R as DsR

/** Notifications from automations: `notify.post`, new proposals from AI apps and the AI limit. */
class AutomationNotifier(
    private val context: Context,
) : NotificationPoster {
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    override suspend fun post(
        title: String,
        text: String?,
        tag: String?,
    ) {
        show(CHANNEL_POSTS, R.string.automation_channel_posts, tag ?: title, title, text, openIntent(null))
    }

    /** An AI app proposed [rule]; tapping opens the Proposals list. */
    fun proposed(rule: Rule) {
        show(
            CHANNEL_PROPOSALS,
            R.string.automation_channel_proposals,
            "proposal:${rule.id}",
            context.getString(R.string.automation_proposal_title, rule.proposedBy.orEmpty()),
            rule.name,
            openIntent(MainActivity.OPEN_PROPOSALS),
        )
    }

    /** An AI app changed [rule] in place; tapping opens the Automations list. */
    fun changed(
        rule: Rule,
        by: String,
    ) {
        show(
            CHANNEL_PROPOSALS,
            R.string.automation_channel_proposals,
            "changed:${rule.id}",
            context.getString(R.string.automation_changed_title, by),
            rule.name,
            openIntent(MainActivity.OPEN_AUTOMATIONS),
        )
    }

    fun limitReached() {
        show(
            CHANNEL_PROPOSALS,
            R.string.automation_channel_proposals,
            "ai-limit",
            context.getString(R.string.automation_limit_title),
            context.getString(R.string.automation_limit_text),
            openIntent(MainActivity.OPEN_AUTOMATIONS),
        )
    }

    private fun show(
        channel: String,
        channelName: Int,
        tag: String,
        title: String,
        text: String?,
        intent: PendingIntent,
    ) {
        manager.createNotificationChannel(
            NotificationChannel(channel, context.getString(channelName), NotificationManager.IMPORTANCE_DEFAULT),
        )
        val notification =
            Notification
                .Builder(context, channel)
                .setSmallIcon(DsR.drawable.core_designsystem_ic_bolt)
                .setContentTitle(title)
                .apply { if (!text.isNullOrBlank()) setContentText(text).setStyle(Notification.BigTextStyle().bigText(text)) }
                .setContentIntent(intent)
                .setAutoCancel(true)
                .build()
        runCatching { manager.notify(tag, NOTIFICATION_ID, notification) }
    }

    private fun openIntent(open: String?): PendingIntent =
        PendingIntent.getActivity(
            context,
            open.hashCode(),
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .apply { if (open != null) putExtra(MainActivity.EXTRA_OPEN, open) },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private companion object {
        const val CHANNEL_POSTS = "automation-posts"
        const val CHANNEL_PROPOSALS = "automation-proposals"
        const val NOTIFICATION_ID = 7
    }
}
