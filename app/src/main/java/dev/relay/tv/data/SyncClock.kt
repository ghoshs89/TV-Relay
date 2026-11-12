package dev.relay.tv.data

/**
 * Times for synced changes. Wall clocks on TVs drift, so every time handed out is later than the one
 * before it and later than any time seen from another screen. A screen whose clock is a day slow
 * therefore cannot make its changes lose to older ones for ever, and one whose clock is fast cannot
 * make them win for ever: once the others have seen its times, their own changes are later still.
 */
class SyncClock(last: Long = 0L) {
    /** The latest time issued or seen; saved with the sync state so it never goes back after a restart. */
    var last: Long = last
        private set

    /** A time for a change made now: the clock's reading, but always after the last one. */
    @Synchronized
    fun next(now: Long): Long {
        last = maxOf(now, last + 1)
        return last
    }

    /** A time seen on another screen's change. */
    @Synchronized
    fun observe(seen: Long) {
        if (seen > last) last = seen
    }
}
