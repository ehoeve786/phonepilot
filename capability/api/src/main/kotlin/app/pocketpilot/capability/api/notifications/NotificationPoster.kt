package app.pocketpilot.capability.api.notifications

/** Posts PocketPilot's own notifications, for `notify.post` and automations. */
fun interface NotificationPoster {
    /** Shows [title] and [text]; a notification with the same [tag] replaces the earlier one. */
    suspend fun post(
        title: String,
        text: String?,
        tag: String?,
    )
}
