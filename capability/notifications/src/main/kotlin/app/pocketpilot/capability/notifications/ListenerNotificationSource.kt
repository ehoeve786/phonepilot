package app.pocketpilot.capability.notifications

import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.service.notification.StatusBarNotification
import app.pocketpilot.capability.api.BackendIds
import app.pocketpilot.capability.api.notifications.NotificationAction
import app.pocketpilot.capability.api.notifications.NotificationCommand
import app.pocketpilot.capability.api.notifications.NotificationSource
import app.pocketpilot.capability.api.notifications.PhoneNotification
import app.pocketpilot.core.model.ToolErrorCode
import app.pocketpilot.core.model.ToolException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * READ_NOTIFICATIONS through [PocketPilotNotificationListener]. Group summaries and ongoing
 * notifications without text (media, downloads) are left out; the rest are listed newest first.
 */
object ListenerNotificationSource : NotificationSource {
    override val backendId: String = BackendIds.NOTIFICATION_LISTENER

    private val current = MutableStateFlow<PocketPilotNotificationListener?>(null)
    private val connected = MutableStateFlow(false)
    override val available: StateFlow<Boolean> = connected.asStateFlow()

    /** The component to allow under Notification access, for Doctor's Shizuku fix. */
    fun component(context: Context): ComponentName = ComponentName(context, PocketPilotNotificationListener::class.java)

    internal fun attach(listener: PocketPilotNotificationListener) {
        current.value = listener
        connected.value = true
    }

    internal fun detach(listener: PocketPilotNotificationListener) {
        if (current.compareAndSet(listener, null)) connected.value = false
    }

    private val changes =
        MutableSharedFlow<NotificationChange>(extraBufferCapacity = CHANGE_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Notifications as they arrive and go, for automation triggers. Group summaries and empty ones are left out. */
    val events: SharedFlow<NotificationChange> = changes.asSharedFlow()

    internal fun onPosted(sbn: StatusBarNotification) = emit(sbn, removed = false)

    internal fun onRemoved(sbn: StatusBarNotification) = emit(sbn, removed = true)

    private fun emit(
        sbn: StatusBarNotification,
        removed: Boolean,
    ) {
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val notification = runCatching { sbn.toPhoneNotification() }.getOrNull() ?: return
        if (notification.title == null && notification.text == null) return
        changes.tryEmit(NotificationChange(notification, removed))
    }

    private const val CHANGE_BUFFER = 64
    private const val MAX_EXTRA_CHARS = 2_000

    override suspend fun active(): List<PhoneNotification> =
        listener()
            .activeNotifications
            .orEmpty()
            .filter { it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
            .map { it.toPhoneNotification() }
            .filter { it.title != null || it.text != null }
            .sortedByDescending { it.postedAtMillis }

    override suspend fun act(
        key: String,
        command: NotificationCommand,
    ) {
        val listener = listener()
        val sbn = listener.activeNotifications.orEmpty().firstOrNull { it.key == key } ?: notFound(key)
        when (command) {
            NotificationCommand.Open -> {
                val intent =
                    sbn.notification.contentIntent
                        ?: throw ToolException(ToolErrorCode.INVALID_ARGUMENTS, "This notification does not open anything")
                send(intent, listener, null)
                if (sbn.notification.flags and Notification.FLAG_AUTO_CANCEL != 0) listener.cancelNotification(key)
            }

            NotificationCommand.Dismiss -> {
                listener.cancelNotification(key)
            }

            is NotificationCommand.Action -> {
                val action =
                    sbn.notification.actions
                        .orEmpty()
                        .firstOrNull { it.title?.toString().equals(command.title, ignoreCase = true) }
                        ?: notFound(key)
                val fill =
                    command.text?.let { text ->
                        val inputs = action.remoteInputs.orEmpty()
                        if (inputs.isEmpty()) throw ToolException(ToolErrorCode.INVALID_ARGUMENTS, "'${command.title}' does not take text")
                        Intent().also { intent ->
                            RemoteInput.addResultsToIntent(
                                inputs,
                                intent,
                                Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, text) } },
                            )
                        }
                    }
                send(action.actionIntent, listener, fill)
            }
        }
    }

    private fun send(
        intent: PendingIntent,
        context: Context,
        fill: Intent?,
    ) {
        try {
            intent.send(context, 0, fill, null, null, null, backgroundStartAllowed())
        } catch (e: PendingIntent.CanceledException) {
            throw ToolException(ToolErrorCode.ELEMENT_NOT_FOUND, "The app withdrew that notification's action", "Call notify.list again.")
        }
    }

    /**
     * Android 14 and later block a PendingIntent sent from the background from opening an activity
     * unless the sender allows it, which tapping a notification needs.
     */
    private fun backgroundStartAllowed(): Bundle? =
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA -> {
                ActivityOptions
                    .makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS)
                    .toBundle()
            }

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                @Suppress("DEPRECATION")
                ActivityOptions
                    .makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                    .toBundle()
            }

            else -> {
                null
            }
        }

    private fun listener(): PocketPilotNotificationListener =
        current.value ?: throw ToolException(
            ToolErrorCode.CAPABILITY_UNAVAILABLE,
            "PocketPilot has no notification access",
            "Ask the phone's owner to allow PocketPilot under Settings > Notifications > Notification access, or to tap Fix in Doctor.",
        )

    private fun notFound(key: String): Nothing =
        throw ToolException(
            ToolErrorCode.ELEMENT_NOT_FOUND,
            "No notification with key $key",
            "Call notify.list again; it may have been cleared.",
        )

    private fun StatusBarNotification.toPhoneNotification(): PhoneNotification {
        val extras = notification.extras
        val text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT)
        return PhoneNotification(
            key = key,
            packageName = packageName,
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            text = text?.toString(),
            postedAtMillis = postTime,
            clearable = isClearable,
            opens = notification.contentIntent != null,
            actions =
                notification.actions.orEmpty().mapNotNull { action ->
                    action.title?.toString()?.let { NotificationAction(it, acceptsText = !action.remoteInputs.isNullOrEmpty()) }
                },
            extras = extraTexts(),
        )
    }

    /**
     * Everything the notification carries, under the name the system gives it, so the owner can read the whole
     * payload: every extra as text, the ids and group, and the package that owns the tap target. Pictures are
     * named, not dumped.
     */
    private fun StatusBarNotification.extraTexts(): Map<String, String> {
        val texts = sortedMapOf<String, String>()
        texts["id"] = id.toString()
        tag?.let { texts["tag"] = it }
        groupKey?.let { texts["groupKey"] = it }
        notification.group?.let { texts["group"] = it }
        notification.sortKey?.let { texts["sortKey"] = it }
        notification.tickerText?.let { texts["ticker"] = it.toString() }
        notification.category?.let { texts["category"] = it }
        notification.channelId?.let { texts["channel"] = it }
        notification.contentIntent?.creatorPackage?.let { texts["tapTargetPackage"] = it }
        val extras = notification.extras
        extras.keySet().sorted().forEach { key ->
            val value = runCatching { extras.get(key) }.getOrNull() ?: return@forEach
            val text =
                when (value) {
                    is Array<*> -> value.joinToString("\n") { it.toString() }
                    is Bitmap, is Icon -> "(${value.javaClass.simpleName})"
                    else -> value.toString()
                }
            if (text.isNotBlank()) texts[key] = text.take(MAX_EXTRA_CHARS)
        }
        return texts
    }
}

/** A notification that arrived, or went away when [removed]. */
data class NotificationChange(
    val notification: PhoneNotification,
    val removed: Boolean,
)
