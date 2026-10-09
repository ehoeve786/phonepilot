package app.pocketpilot.capability.api.screen

import app.pocketpilot.capability.api.CapabilityBackend

/** Where an action lands: an element from a snapshot, or a point in screen pixels. */
sealed interface Target {
    /** [snapshotId] null means the latest snapshot. */
    data class OnElement(
        val elementId: String,
        val snapshotId: String? = null,
    ) : Target

    data class AtPoint(
        val x: Int,
        val y: Int,
    ) : Target
}

enum class SwipeDirection { UP, DOWN, LEFT, RIGHT }

enum class GlobalAction { BACK, HOME, RECENTS, NOTIFICATIONS, QUICK_SETTINGS, LOCK_SCREEN }

/** Keys `ui.press_key` accepts by name. */
enum class Key { BACK, HOME, ENTER, DEL, TAB }

/**
 * INJECT_INPUT: taps, gestures and text. Element targets throw ToolException STALE_ELEMENT when the
 * snapshot or element is no longer known, and CAPABILITY_UNAVAILABLE when the backend is off.
 */
interface InputController : CapabilityBackend {
    suspend fun tap(target: Target)

    suspend fun longPress(
        target: Target,
        durationMs: Long,
    )

    suspend fun swipe(
        fromX: Int,
        fromY: Int,
        toX: Int,
        toY: Int,
        durationMs: Long,
    )

    /** Swipes inside the element's bounds; [direction] is the direction the finger moves. */
    suspend fun swipeOn(
        target: Target.OnElement,
        direction: SwipeDirection,
        durationMs: Long,
    )

    /** Replaces the text of [target], or of the focused field when [target] is null. */
    suspend fun typeText(
        text: String,
        target: Target.OnElement?,
        submit: Boolean,
    )

    suspend fun pressKey(key: Key)

    suspend fun globalAction(action: GlobalAction)
}

/** A swipe across [bounds] in [direction], kept 15% away from the edges so it starts inside the element. */
fun swipePoints(
    bounds: Bounds,
    direction: SwipeDirection,
): IntArray {
    val insetX = bounds.width * 15 / 100
    val insetY = bounds.height * 15 / 100
    val cx = bounds.centerX
    val cy = bounds.centerY
    return when (direction) {
        SwipeDirection.UP -> intArrayOf(cx, bounds.bottom - insetY, cx, bounds.top + insetY)
        SwipeDirection.DOWN -> intArrayOf(cx, bounds.top + insetY, cx, bounds.bottom - insetY)
        SwipeDirection.LEFT -> intArrayOf(bounds.right - insetX, cy, bounds.left + insetX, cy)
        SwipeDirection.RIGHT -> intArrayOf(bounds.left + insetX, cy, bounds.right - insetX, cy)
    }
}
