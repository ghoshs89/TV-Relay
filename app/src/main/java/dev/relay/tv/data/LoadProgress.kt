package dev.relay.tv.data

/** What a library load or refresh is doing, shown in the top bar, on Home and in Settings. */
data class LoadProgress(val step: String, val percent: Int?) {
    /** "42% · Downloading movies" or "Downloading · 23.4 MB" when the size isn't known. */
    val text: String get() = if (percent != null) "$percent% · $step" else step
}

/**
 * Percentages for a load. A refresh spends most of its time downloading, so downloads cover
 * 0 to 80%; checking the lists 80 to 90%; preparing them for the screens 90 to 100%. Loading the
 * saved library skips the download.
 */
object LoadSteps {
    const val DOWNLOAD_END = 80
    const val CHECK_END = 90

    fun span(from: Int, to: Int, fraction: Float): Int =
        (from + (to - from) * fraction.coerceIn(0f, 1f)).toInt().coerceIn(from, to)

    /**
     * Share of the download done: bytes so far against each list's expected size. The expected
     * size is the server's Content-Length, else the size of the list saved last time. Small lists
     * with no estimate count as done; if a big list (channels, movies, series) has none, the
     * share is unknown (null) and the screen shows megabytes instead.
     */
    fun downloadFraction(lists: List<Download>): Float? {
        if (lists.any { it.big && it.expected == null && !it.finished }) return null
        var done = 0L
        var total = 0L
        for (d in lists) {
            val bytes = d.bytes
            val expected = d.expected
            val exp = when {
                d.finished -> bytes
                expected != null -> maxOf(expected, bytes)
                else -> bytes
            }
            done += minOf(bytes, exp)
            total += exp
        }
        if (total <= 0) return 0f
        val f = done.toFloat() / total
        // Never show 100% until every list has finished.
        return if (lists.all { it.finished }) 1f else minOf(f, 0.99f)
    }

    class Download(val action: String, val big: Boolean) {
        @Volatile var bytes = 0L
        @Volatile var expected: Long? = null
        @Volatile var finished = false
    }

    fun megabytes(bytes: Long): String = "%.1f MB".format(bytes / 1_048_576.0)
}
