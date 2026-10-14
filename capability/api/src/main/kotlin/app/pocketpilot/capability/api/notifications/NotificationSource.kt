package app.pocketpilot.capability.api.notifications

import app.pocketpilot.capability.api.CapabilityBackend

/** A button on a notification, such as Reply or Mark as read. */
data class NotificationAction(
    val title: String,
    /** True when the action takes typed text, like a messaging app's Reply. */
    val acceptsText: Boolean,
)

/** One notification in the shade (spec section 5, `notify.list`). Title and text come from the posting app. */
data class PhoneNotification(
    /** The system's key; [NotificationSource.act] takes it back. */
    val key: String,
    val packageName: String,
    val title: String?,
    val text: String?,
    val postedAtMillis: Long,
    /** True when the owner can swipe it away. */
    val clearable: Boolean,
    /** True when tapping the notification opens something. */
    val opens: Boolean,
    val actions: List<NotificationAction>,
)

/** What to do with a notification. */
sealed interface NotificationCommand {
    /** Taps the notification itself. */
    data object Open : NotificationCommand

    data object Dismiss : NotificationCommand

    /** Taps the action titled [title], typing [text] first when the action takes text. */
    data class Action(
        val title: String,
        val text: String?,
    ) : NotificationCommand
}

/** Reads the notifications in the shade and acts on them, through the notification listener. */
interface NotificationSource : CapabilityBackend {
    /** Newest first. */
    suspend fun active(): List<PhoneNotification>

    /** Throws ToolException ELEMENT_NOT_FOUND when [key] is gone or has no such action. */
    suspend fun act(
        key: String,
        command: NotificationCommand,
    )
}
