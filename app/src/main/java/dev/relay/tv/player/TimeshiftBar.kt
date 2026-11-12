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
 * The "behind live" bar over the video. Phones get -10s, +30s and Live buttons; TVs use the
 * remote's Left and Right, so the bar is only text there.
 */
class TimeshiftBar(
    private val context: Context,
    touchControls: Boolean,
    onSeek: (Int) -> Unit,
    onLive: () -> Unit,
) {
    private val text = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 16f
        setPadding(context.tvPx(28), context.tvPx(16), context.tvPx(28), context.tvPx(16))
    }

    private val bar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(0xDD0B1220.toInt())
        visibility = View.GONE
        addView(text)
        if (touchControls) {
            listOf("−10s" to -10, "+30s" to 30, "Live" to Int.MAX_VALUE).forEach { (label, delta) ->
                addView(
                    TextView(context).apply {
                        this.text = label
                        setTextColor(0xFF3DDC97.toInt())
                        textSize = 16f
                        setPadding(context.tvPx(28), context.tvPx(16), context.tvPx(28), context.tvPx(16))
                        isFocusable = false
                        setOnClickListener { if (delta == Int.MAX_VALUE) onLive() else onSeek(delta) }
                    },
                )
            }
        }
    }

    fun addTo(root: FrameLayout) {
        root.addView(
            bar,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ).apply { setMargins(context.tvPx(48), 0, context.tvPx(48), context.tvPx(120)) },
        )
    }

    fun show(label: String) {
        text.text = label
        bar.visibility = View.VISIBLE
    }

    fun hide() {
        bar.visibility = View.GONE
    }
}
