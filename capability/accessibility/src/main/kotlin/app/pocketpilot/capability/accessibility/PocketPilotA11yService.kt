package app.pocketpilot.capability.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * The Accessibility service the owner turns on in system settings. It holds no logic: it hands itself
 * and its events to [A11yBridge], where the READ_UI, INJECT_INPUT and CAPTURE_SCREEN backends find it.
 */
class PocketPilotA11yService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        A11yBridge.attach(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        A11yBridge.onEvent(event)
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        A11yBridge.detach(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        A11yBridge.detach(this)
        super.onDestroy()
    }
}
