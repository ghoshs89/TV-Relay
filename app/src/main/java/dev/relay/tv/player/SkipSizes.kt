package dev.relay.tv.player

/** The jump sizes offered for the Left and Right keys while watching, in seconds. */
object SkipSizes {
    val OPTIONS = listOf(5, 10, 15, 30, 60)
    const val DEFAULT_BACK = 10
    const val DEFAULT_FORWARD = 30

    /** The next size in the list after [current], wrapping to the first; an unknown size starts again from the first. */
    fun next(current: Int): Int {
        val i = OPTIONS.indexOf(current)
        return if (i < 0) OPTIONS[0] else OPTIONS[(i + 1) % OPTIONS.size]
    }

    /** Whatever was saved, kept to something the player can use. */
    fun clean(seconds: Int, fallback: Int): Int = if (seconds in OPTIONS) seconds else fallback

    fun label(seconds: Int): String = "$seconds seconds"
}
