package dev.relay.tv.player

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import dev.relay.tv.data.Reminder
import dev.relay.tv.ui.ReminderCenter

/**
 * The "programme starting" banner over the video. It hides itself after 30 seconds; OK (TV) or a
 * tap (phone) calls [onWatch], and Back dismisses it.
 */
class ReminderBanner(
    private val context: Context,
    private val tv: Boolean,
    private val handler: Handler,
    private val onWatch: (Reminder) -> Unit,
) {
    /** The reminder on screen now, if any. */
    var shown: Reminder? = null
        private set

    private val autoHide = Runnable { dismiss() }

    private val view = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 18f
        setPadding(context.tvPx(32), context.tvPx(20), context.tvPx(32), context.tvPx(20))
        setBackgroundColor(0xEEB8462E.toInt())
        visibility = View.GONE
        isFocusable = false
        setOnClickListener { shown?.let { watch(it) } }
    }

    fun addTo(root: FrameLayout) {
        root.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END,
            ).apply { setMargins(context.tvPx(48), context.tvPx(110), context.tvPx(48), 0) },
        )
    }

    fun show(r: Reminder) {
        shown = r
        view.text = "⏰  ${ReminderCenter.label(r)}\n" + if (tv) "Press OK to watch · Back to dismiss" else "Tap to watch"
        view.visibility = View.VISIBLE
        handler.removeCallbacks(autoHide)
        handler.postDelayed(autoHide, 30_000)
    }

    fun dismiss() {
        view.visibility = View.GONE
        if (ReminderCenter.due == shown) ReminderCenter.due = null
        shown = null
    }

    fun watch(r: Reminder) {
        shown = null
        view.visibility = View.GONE
        onWatch(r)
    }
}
