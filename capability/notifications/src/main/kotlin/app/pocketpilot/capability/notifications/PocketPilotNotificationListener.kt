package app.pocketpilot.capability.notifications

import android.service.notification.NotificationListenerService

/**
 * The notification listener the owner allows under Notification access. Like the Accessibility
 * service it holds no logic: it hands itself to [ListenerNotificationSource] while connected.
 */
class PocketPilotNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        ListenerNotificationSource.attach(this)
    }

    override fun onListenerDisconnected() {
        ListenerNotificationSource.detach(this)
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        ListenerNotificationSource.detach(this)
        super.onDestroy()
    }
}
