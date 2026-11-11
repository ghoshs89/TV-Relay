package dev.relay.tv.player

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The panel over the video when a stream can't be recovered: what went wrong, a hint, and
 * Retry (plus Next channel on live TV). The player decides when to show it; this only draws it.
 */
class FailurePanel(
    private val context: Context,
    onRetry: () -> Unit,
    onNext: () -> Unit,
) {
    private fun button(label: String, onClick: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 18f
        setPadding(40, 20, 40, 20)
        setTextColor(Color.WHITE)
        setBackgroundColor(0xFF1E2B45.toInt())
        isFocusable = true
        setOnFocusChangeListener { v, focused ->
            v.setBackgroundColor(if (focused) 0xFF3DDC97.toInt() else 0xFF1E2B45.toInt())
            (v as TextView).setTextColor(if (focused) Color.BLACK else Color.WHITE)
        }
        setOnClickListener { onClick() }
    }

    private val title = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 20f
        gravity = Gravity.CENTER
        setPadding(0, 0, 0, 8)
    }
    private val hint = TextView(context).apply {
        setTextColor(0xFFB8C2D3.toInt())
        textSize = 15f
        gravity = Gravity.CENTER
        maxWidth = 900
        setPadding(0, 0, 0, 28)
    }
    private val retry = button("Retry") { onRetry() }
    private val next = button("Next channel") { onNext() }

    private val panel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(56, 44, 56, 44)
        setBackgroundColor(0xEE0B1220.toInt())
        visibility = View.GONE
        addView(title)
        addView(hint)
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                addView(retry)
                addView(
                    next,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = 28 },
                )
            },
        )
    }

    val showing: Boolean get() = panel.visibility == View.VISIBLE

    fun addTo(root: FrameLayout) {
        root.addView(
            panel,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
        )
    }

    /** Sets what the panel says; it can be changed again while it is showing. */
    fun setMessage(message: String, hintText: String) {
        title.text = message
        hint.text = hintText
    }

    /** Shows the panel with focus on Retry. [canSkip] adds Next channel. */
    fun show(canSkip: Boolean) {
        next.visibility = if (canSkip) View.VISIBLE else View.GONE
        panel.visibility = View.VISIBLE
        retry.requestFocus()
    }

    /** The kids viewing limit was reached: the only button is Close. */
    fun showTimesUp(reason: String) {
        setMessage("Time's up", "$reason A parent can add time in Settings.")
        retry.text = "Close"
        show(canSkip = false)
    }

    fun hide() {
        panel.visibility = View.GONE
    }
}
