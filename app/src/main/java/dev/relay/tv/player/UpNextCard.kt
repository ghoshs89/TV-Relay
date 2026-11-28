package dev.relay.tv.player

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView

/** When the end-of-film card appears and what it offers. Pure, so the rules can be tested. */
object UpNextRules {
    sealed interface Offer

    /** The next episode in the queue starts by itself in [secondsLeft] (autoNext); OK starts it now. */
    data class NextEpisode(val secondsLeft: Int) : Offer

    /** Nothing follows (a film, a season or series finale): offer the top recommendation; never start it alone. */
    data object Recommend : Offer

    const val WINDOW_MS = 30_000L
    const val MIN_DURATION_MS = 5 * 60_000L

    fun offer(kind: String, durationMs: Long, positionMs: Long, hasNext: Boolean, autoNext: Boolean, hidden: Boolean): Offer? {
        if (hidden || (kind != "movie" && kind != "episode")) return null
        if (durationMs < MIN_DURATION_MS || positionMs <= 0) return null
        val left = durationMs - positionMs
        if (left <= 0 || left > WINDOW_MS) return null
        return when {
            hasNext -> if (autoNext) NextEpisode(((left + 999) / 1000).toInt()) else null
            else -> Recommend
        }
    }
}

/**
 * The card over the last 30 seconds of a film or episode: "Up next in 12 s" for the next episode, or a
 * recommendation after a film or finale. OK (or a tap) plays it now; Back hides it for this title.
 */
class UpNextCard(private val context: Context, private val tv: Boolean, private val onPlayNow: () -> Unit) {
    var shown: Boolean = false
        private set

    private val view = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = if (tv) 18f else 15f
        setPadding(context.tvPx(32), context.tvPx(20), context.tvPx(32), context.tvPx(20))
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(0xEE14171C.toInt())
            cornerRadius = context.tvPx(14).toFloat()
            setStroke(context.tvPx(3), 0xFF3DDC97.toInt())
        }
        maxWidth = (context.resources.displayMetrics.widthPixels * 0.42f).toInt()
        visibility = View.GONE
        isFocusable = false
        setOnClickListener { onPlayNow() }
    }

    fun addTo(root: FrameLayout) {
        root.addView(
            view,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END)
                .apply { setMargins(context.tvPx(48), 0, context.tvPx(48), context.tvPx(140)) },
        )
    }

    fun show(headline: String, title: String) {
        val hint = if (tv) "OK: play now  ·  Back: hide" else "Tap to play now"
        view.text = "$headline\n$title\n$hint"
        view.visibility = View.VISIBLE
        shown = true
    }

    fun hide() {
        if (!shown) return
        view.visibility = View.GONE
        shown = false
    }
}
