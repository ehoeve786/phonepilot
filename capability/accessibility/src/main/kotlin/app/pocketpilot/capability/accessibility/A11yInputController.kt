package app.pocketpilot.capability.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import app.pocketpilot.capability.api.BackendIds
import app.pocketpilot.capability.api.screen.GlobalAction
import app.pocketpilot.capability.api.screen.InputController
import app.pocketpilot.capability.api.screen.Key
import app.pocketpilot.capability.api.screen.SwipeDirection
import app.pocketpilot.capability.api.screen.Target
import app.pocketpilot.core.model.ToolErrorCode
import app.pocketpilot.core.model.ToolException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * INJECT_INPUT through the Accessibility service. Node actions come first, since they work whatever
 * is drawn on top; gestures at the element's centre are the fallback (spec section 6).
 */
class A11yInputController(
    private val reader: A11yScreenReader,
) : InputController {
    override val backendId: String = BackendIds.ACCESSIBILITY
    override val available: StateFlow<Boolean> = A11yBridge.connected

    override suspend fun tap(target: Target) {
        val service = A11yBridge.require()
        when (target) {
            is Target.AtPoint -> {
                gesture(service, point(service, target.x, target.y), TAP_MS)
            }

            is Target.OnElement -> {
                val node = reader.resolve(target)
                val clickable = node.selfOrAncestor { it.isClickable }
                if (clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) != true) {
                    gesture(service, center(service, node), TAP_MS)
                }
            }
        }
    }

    override suspend fun longPress(
        target: Target,
        durationMs: Long,
    ) {
        val service = A11yBridge.require()
        when (target) {
            is Target.AtPoint -> {
                gesture(service, point(service, target.x, target.y), durationMs)
            }

            is Target.OnElement -> {
                val node = reader.resolve(target)
                val pressable = node.selfOrAncestor { it.isLongClickable }
                if (pressable?.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) != true) {
                    gesture(service, center(service, node), durationMs)
                }
            }
        }
    }

    override suspend fun swipe(
        fromX: Int,
        fromY: Int,
        toX: Int,
        toY: Int,
        durationMs: Long,
    ) {
        val service = A11yBridge.require()
        val path = point(service, fromX, fromY).apply { lineTo(toX.toFloat(), toY.toFloat()) }
        checkOnScreen(service, toX, toY)
        gesture(service, path, durationMs)
    }

    override suspend fun swipeOn(
        target: Target.OnElement,
        direction: SwipeDirection,
        durationMs: Long,
    ) {
        val service = A11yBridge.require()
        val bounds = visibleBounds(service, reader.resolve(target))
        val insetX = bounds.width() * EDGE_INSET
        val insetY = bounds.height() * EDGE_INSET
        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()
        val (from, to) =
            when (direction) {
                SwipeDirection.UP -> (cx to bounds.bottom - insetY) to (cx to bounds.top + insetY)
                SwipeDirection.DOWN -> (cx to bounds.top + insetY) to (cx to bounds.bottom - insetY)
                SwipeDirection.LEFT -> (bounds.right - insetX to cy) to (bounds.left + insetX to cy)
                SwipeDirection.RIGHT -> (bounds.left + insetX to cy) to (bounds.right - insetX to cy)
            }
        val path =
            Path().apply {
                moveTo(from.first, from.second)
                lineTo(to.first, to.second)
            }
        gesture(service, path, durationMs)
    }

    override suspend fun typeText(
        text: String,
        target: Target.OnElement?,
        submit: Boolean,
    ) {
        val service = A11yBridge.require()
        var field = target?.let(reader::resolve) ?: focusedInput(service)
        if (!field.isEditable) {
            // Search bars are often a button that opens the real field: tap it and use what gets focus.
            field.selfOrAncestor { it.isClickable }?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            delay(FOCUS_SETTLE_MS)
            field = focusedInput(service)
            if (!field.isEditable) {
                throw ToolException(
                    ToolErrorCode.INVALID_ARGUMENTS,
                    "That element is not a text field",
                    "Pick an element with editable true",
                )
            }
        }
        if (!field.isFocused) field.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        setText(field, text)
        if (submit) imeEnter(field)
    }

    override suspend fun pressKey(key: Key) {
        val service = A11yBridge.require()
        when (key) {
            Key.BACK -> {
                global(service, AccessibilityService.GLOBAL_ACTION_BACK)
            }

            Key.HOME -> {
                global(service, AccessibilityService.GLOBAL_ACTION_HOME)
            }

            Key.ENTER -> {
                imeEnter(focusedInput(service))
            }

            Key.DEL -> {
                val field = focusedInput(service)
                val current = if (field.isShowingHintText) "" else field.text?.toString().orEmpty()
                if (current.isNotEmpty()) setText(field, current.dropLast(1))
            }

            Key.TAB -> {
                val next = focusedInput(service).focusSearch(View.FOCUS_FORWARD)
                if (next?.performAction(AccessibilityNodeInfo.ACTION_FOCUS) != true) {
                    throw ToolException(ToolErrorCode.ELEMENT_NOT_FOUND, "There is no next field to move to")
                }
            }
        }
    }

    override suspend fun globalAction(action: GlobalAction) {
        val service = A11yBridge.require()
        val id =
            when (action) {
                GlobalAction.BACK -> AccessibilityService.GLOBAL_ACTION_BACK
                GlobalAction.HOME -> AccessibilityService.GLOBAL_ACTION_HOME
                GlobalAction.RECENTS -> AccessibilityService.GLOBAL_ACTION_RECENTS
                GlobalAction.NOTIFICATIONS -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
                GlobalAction.QUICK_SETTINGS -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
                GlobalAction.LOCK_SCREEN -> AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
            }
        global(service, id)
    }

    private fun global(
        service: AccessibilityService,
        id: Int,
    ) {
        if (!service.performGlobalAction(id)) throw ToolException(ToolErrorCode.INTERNAL_ERROR, "The system refused the action")
    }

    private fun focusedInput(service: AccessibilityService): AccessibilityNodeInfo =
        service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: throw ToolException(
                ToolErrorCode.ELEMENT_NOT_FOUND,
                "No text field has focus",
                "Pass element with the ID of the text field from screen.snapshot",
            )

    private fun setText(
        field: AccessibilityNodeInfo,
        text: String,
    ) {
        val arguments = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        if (!field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
            throw ToolException(ToolErrorCode.INTERNAL_ERROR, "The text field did not accept the text")
        }
    }

    private fun imeEnter(field: AccessibilityNodeInfo) {
        if (!field.performAction(AccessibilityAction.ACTION_IME_ENTER.id)) {
            throw ToolException(
                ToolErrorCode.CAPABILITY_UNAVAILABLE,
                "This field has no keyboard action to submit with",
                "Tap the screen's search, send or done button instead",
            )
        }
    }

    private inline fun AccessibilityNodeInfo.selfOrAncestor(test: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        var node: AccessibilityNodeInfo? = this
        repeat(MAX_ANCESTORS) {
            val current = node ?: return null
            if (test(current)) return current
            node = current.parent
        }
        return null
    }

    private fun visibleBounds(
        service: AccessibilityService,
        node: AccessibilityNodeInfo,
    ): Rect {
        val rect = Rect().also(node::getBoundsInScreen)
        val size = reader.screenSize(service)
        if (!rect.intersect(0, 0, size.width, size.height) || rect.isEmpty) {
            throw ToolException(ToolErrorCode.ELEMENT_NOT_FOUND, "That element is off screen", "Scroll it into view with ui.swipe first")
        }
        return rect
    }

    private fun center(
        service: AccessibilityService,
        node: AccessibilityNodeInfo,
    ): Path {
        val rect = visibleBounds(service, node)
        return Path().apply { moveTo(rect.exactCenterX(), rect.exactCenterY()) }
    }

    private fun point(
        service: AccessibilityService,
        x: Int,
        y: Int,
    ): Path {
        checkOnScreen(service, x, y)
        return Path().apply { moveTo(x.toFloat(), y.toFloat()) }
    }

    private fun checkOnScreen(
        service: AccessibilityService,
        x: Int,
        y: Int,
    ) {
        val size = reader.screenSize(service)
        if (x !in 0 until size.width || y !in 0 until size.height) {
            throw ToolException(
                ToolErrorCode.INVALID_ARGUMENTS,
                "($x, $y) is outside the ${size.width}x${size.height} screen",
                "Use screen pixels; convert screen.capture image pixels with its transform",
            )
        }
    }

    private suspend fun gesture(
        service: AccessibilityService,
        path: Path,
        durationMs: Long,
    ) {
        val description = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, durationMs)).build()
        suspendCancellableCoroutine { continuation ->
            val callback =
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription) = continuation.resume(Unit)

                    override fun onCancelled(gestureDescription: GestureDescription) =
                        continuation.resumeWithException(
                            ToolException(
                                ToolErrorCode.INTERNAL_ERROR,
                                "The system cancelled the gesture",
                                "Retry once the screen is idle",
                            ),
                        )
                }
            if (!service.dispatchGesture(description, callback, null)) {
                continuation.resumeWithException(ToolException(ToolErrorCode.INTERNAL_ERROR, "The system refused the gesture"))
            }
        }
    }

    private companion object {
        const val TAP_MS = 50L
        const val EDGE_INSET = 0.15f
        const val MAX_ANCESTORS = 8
        const val FOCUS_SETTLE_MS = 400L
    }
}
