package app.pocketpilot.capability.accessibility

import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
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

    /** The bar of floating buttons, if any are up. */
    private var buttonBar: View? = null

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
     * Shows one button per entry down the right edge, replacing any shown before. A tap runs the
     * entry's action. Returns false when the Accessibility service is off, so nothing is shown.
     */
    fun showButtons(buttons: List<Pair<String, () -> Unit>>): Boolean {
        val service = A11yBridge.service() ?: return false
        main.post {
            removeButtons()
            if (buttons.isEmpty()) return@post
            val dp = { value: Int ->
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), service.resources.displayMetrics).toInt()
            }
            val bar = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL }
            buttons.forEach { (text, onTap) ->
                val button =
                    TextView(service).apply {
                        this.text = text
                        setTextColor(Color.WHITE)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                        setPadding(dp(14), dp(10), dp(14), dp(10))
                        background =
                            GradientDrawable().apply {
                                cornerRadius = dp(18).toFloat()
                                setColor(Color.argb(230, 32, 33, 36))
                                setStroke(dp(1), Color.rgb(229, 57, 53))
                            }
                        setOnClickListener { onTap() }
                    }
                bar.addView(
                    button,
                    LinearLayout.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = dp(8)
                    },
                )
            }
            val params =
                WindowManager
                    .LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT,
                    ).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
            val manager = service.getSystemService(WindowManager::class.java)
            runCatching { manager.addView(bar, params) }.onSuccess { buttonBar = bar }
        }
        return true
    }

    /** Removes the floating buttons if they are up. */
    fun hideButtons() {
        main.post { removeButtons() }
    }

    private fun removeButtons() {
        val bar = buttonBar ?: return
        runCatching { bar.context.getSystemService(WindowManager::class.java).removeView(bar) }
        buttonBar = null
    }

    private const val TOP_OFFSET_DP = 40
}
