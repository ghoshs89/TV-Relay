package dev.relay.tv.ui

/** Column widths for the Live TV screen, worked out from the real screen width. Pure, so it can be tested. */
object LiveLayout {
    class Widths(val category: Float, val panel: Float)

    /**
     * [width] is the whole screen width in dp and [side] the padding on each side. The categories and
     * the channel panel take shares of what is left, within limits, and the panel gives way so
     * the channel list keeps at least 45% of it ([gap] is the space between the three columns).
     */
    fun widths(width: Float, side: Float, gap: Float = 28f): Widths {
        val usable = (width - 2 * side).coerceAtLeast(0f)
        val category = (usable * 0.19f).coerceIn(150f, 240f)
        val wanted = (usable * 0.28f).coerceIn(240f, 340f)
        val room = usable * 0.55f - category - gap
        return Widths(category, wanted.coerceAtMost(room).coerceAtLeast(200f))
    }
}
