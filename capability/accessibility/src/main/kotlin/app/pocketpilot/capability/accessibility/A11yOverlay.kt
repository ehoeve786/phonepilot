package app.pocketpilot.capability.accessibility

import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The "PocketPilot is controlling this phone" pill (spec section 8): a small banner at the top of the
 * screen with a Stop button, drawn as an Accessibility overlay so it needs no extra permission.
 * Screen snapshots leave Accessibility overlays out, so models never see or tap it by element ID.
 */
object A11yOverlay {
    private val main = Handler(Looper.getMainLooper())
    private var shown: View? = null
    private var label: TextView? = null

    /** The service [shown] was added with; its window goes away with that service. */
    private var owner: PocketPilotA11yService? = null

    /** The floating buttons that are up. */
    private val buttonViews = mutableListOf<View>()

    /**
     * Shows the pill with [text], or updates it if it is already up. Returns false when the
     * Accessibility service is off, so the caller can fall back to a notification.
     */
    fun show(
        text: String,
        stopLabel: String,
        onStop: () -> Unit,
    ): Boolean {
        val service = A11yBridge.service() ?: return false
        main.post {
            if (shown != null && owner === service) {
                label?.text = text
                return@post
            }
            val manager = service.getSystemService(WindowManager::class.java)
            val dp = { value: Int ->
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), service.resources.displayMetrics).toInt()
            }
            val message =
                TextView(service).apply {
                    this.text = text
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setPadding(dp(12), dp(6), dp(8), dp(6))
                }
            val stop =
                TextView(service).apply {
                    this.text = stopLabel
                    setTextColor(Color.rgb(255, 138, 128))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setPadding(dp(8), dp(6), dp(12), dp(6))
                    setOnClickListener { onStop() }
                }
            val pill =
                LinearLayout(service).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background =
                        GradientDrawable().apply {
                            cornerRadius = dp(18).toFloat()
                            setColor(Color.argb(230, 32, 33, 36))
                            setStroke(dp(1), Color.rgb(229, 57, 53))
                        }
                    addView(message)
                    addView(stop)
                }
            val params =
                WindowManager
                    .LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT,
                    ).apply {
                        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                        // Below the status bar, clear of the notification shade's pull-down edge.
                        y = dp(TOP_OFFSET_DP)
                    }
            runCatching { manager.addView(pill, params) }.onSuccess {
                shown = pill
                label = message
                owner = service
            }
        }
        return true
    }

    /** Removes the pill if it is up. */
    fun hide() {
        main.post {
            val view = shown ?: return@post
            runCatching { view.context.getSystemService(WindowManager::class.java).removeView(view) }
            shown = null
            label = null
            owner = null
        }
    }

    /**
     * A floating button: its text, how it looks, where it sits and what its presses do. A button with
     * no [x] or [y] stacks down the right edge; [x] and [y] are pixels from the top left.
     */
    class FloatingButton(
        val text: String,
        /** A drawable to show instead of [text], or null for text. */
        val icon: Drawable?,
        val small: Boolean,
        val transparent: Boolean,
        /** From 0 (invisible) to 1 (solid). */
        val opacity: Float,
        val x: Int?,
        val y: Int?,
        val vibrate: Boolean,
        val onTap: () -> Unit,
        val onLongPress: () -> Unit,
    )

    /**
     * Shows [buttons] over other apps, replacing any shown before. Returns false when the Accessibility
     * service is off, so nothing is shown.
     */
    fun showButtons(buttons: List<FloatingButton>): Boolean {
        val service = A11yBridge.service() ?: return false
        main.post {
            removeButtons()
            val manager = service.getSystemService(WindowManager::class.java)
            val dp = { value: Int ->
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), service.resources.displayMetrics).toInt()
            }
            buttons.forEachIndexed { index, button ->
                val view =
                    TextView(service).apply {
                        text = if (button.icon == null) button.text else ""
                        setTextColor(Color.WHITE)
                        setCompoundDrawablesWithIntrinsicBounds(button.icon, null, null, null)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (button.small) 11f else 13f)
                        val padding = if (button.small) 6 else 10
                        setPadding(dp(padding + 4), dp(padding), dp(padding + 4), dp(padding))
                        background =
                            if (button.transparent) {
                                null
                            } else {
                                GradientDrawable().apply {
                                    cornerRadius = dp(18).toFloat()
                                    setColor(Color.argb(230, 32, 33, 36))
                                    setStroke(dp(1), Color.rgb(229, 57, 53))
                                }
                            }
                        alpha = button.opacity
                        setOnClickListener {
                            if (button.vibrate) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            button.onTap()
                        }
                        setOnLongClickListener {
                            if (button.vibrate) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            button.onLongPress()
                            true
                        }
                    }
                val params =
                    WindowManager
                        .LayoutParams(
                            WindowManager.LayoutParams.WRAP_CONTENT,
                            WindowManager.LayoutParams.WRAP_CONTENT,
                            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                            PixelFormat.TRANSLUCENT,
                        ).apply {
                            if (button.x == null || button.y == null) {
                                gravity = Gravity.TOP or Gravity.END
                                x = dp(12)
                                y = dp(STACK_TOP_DP) + index * dp(STACK_STEP_DP)
                            } else {
                                gravity = Gravity.TOP or Gravity.START
                                x = button.x
                                y = button.y
                            }
                        }
                runCatching { manager.addView(view, params) }.onSuccess { buttonViews += view }
            }
        }
        return true
    }

    /** Removes the floating buttons if they are up. */
    fun hideButtons() {
        main.post { removeButtons() }
    }

    private fun removeButtons() {
        buttonViews.forEach { view ->
            runCatching { view.context.getSystemService(WindowManager::class.java).removeView(view) }
        }
        buttonViews.clear()
    }

    private const val TOP_OFFSET_DP = 40
    private const val STACK_TOP_DP = 200
    private const val STACK_STEP_DP = 64
}
