package dev.relay.tv.data

/** When saved data is old enough to fetch again. */
object Freshness {
    const val LIBRARY_MS = 6 * 3600_000L
    const val CHECK_EVERY_MS = 30 * 60_000L

    /**
     * How long an overdue library waits after start-up before it is refreshed, so the first
     * minute of browsing is not shared with downloading and checking 100 MB of lists.
     */
    const val START_DELAY_MS = 20_000L

    fun libraryDue(savedAt: Long, now: Long): Boolean = savedAt <= 0L || now - savedAt >= LIBRARY_MS

    /**
     * Milliseconds to wait before a refresh that is [due]. Only the first library installed in a
     * process waits ([installedAt], on the same clock as [now]; negative when there was none), and
     * only until [START_DELAY_MS] after it was installed; a refresh the user asked for ([force])
     * never waits.
     */
    fun startDelay(installedAt: Long, now: Long, due: Boolean, force: Boolean): Long {
        if (force || !due || installedAt < 0) return 0L
        return (installedAt + START_DELAY_MS - now).coerceIn(0L, START_DELAY_MS)
    }
}
