package dev.relay.tv.player

/**
 * Ends playback after a set time, for falling asleep in front of the TV. One minute before the end
 * it warns; any key press or touch in that last minute adds another full period.
 *
 * Times are a monotonic clock in ms. [limitMs] of 0 means off.
 */
class SleepTimer(private val limitMs: Long, start: Long) {

    enum class Event { None, Warn, Stop }

    private var deadline = start + limitMs
    private var warned = false
    private var stopped = false

    val enabled: Boolean get() = limitMs > 0

    /** Called about once a second. Warn and Stop are each returned once. */
    fun tick(now: Long): Event {
        if (!enabled || stopped) return Event.None
        val left = deadline - now
        if (left <= 0) {
            stopped = true
            return Event.Stop
        }
        if (left <= WARN_MS && !warned) {
            warned = true
            return Event.Warn
        }
        return Event.None
    }

    /** Any input. Restarts the period when it arrives during the warning; returns whether it did. */
    fun keepWatching(now: Long): Boolean {
        if (!enabled || stopped || deadline - now > WARN_MS) return false
        deadline = now + limitMs
        warned = false
        return true
    }

    companion object {
        const val WARN_MS = 60_000L

        /** Minutes offered in Settings; 0 is off. */
        val OPTIONS = listOf(0, 30, 60, 90, 120)

        /** The next choice after [current], wrapping to the first; an unknown value becomes off. */
        fun next(current: Int): Int {
            val i = OPTIONS.indexOf(current)
            return if (i < 0) OPTIONS[0] else OPTIONS[(i + 1) % OPTIONS.size]
        }

        fun clean(minutes: Int): Int = if (minutes in OPTIONS) minutes else 0

        fun label(minutes: Int): String = if (clean(minutes) == 0) "Off" else "${clean(minutes)} minutes"
    }
}

/**
 * Asks "Are you still watching?" so a TV left running overnight does not play to nobody: after
 * [EPISODES] episodes in a row that started by themselves, or after [LIVE_IDLE_MS] of live TV with
 * no key press. If the question goes unanswered for [ANSWER_MS], playback is told to stop.
 */
class StillWatching(private val enabled: Boolean, start: Long) {

    enum class Event { None, Ask, Stop }

    private var lastInput = start
    private var autoEpisodes = 0
    private var askedAt = 0L
    private var stopped = false

    var asking = false
        private set

    /** The viewer pressed a key or touched the screen. Does not count as an answer while asking. */
    fun onInput(now: Long) {
        if (asking) return
        lastInput = now
        autoEpisodes = 0
    }

    /** The next episode began by itself. Returns true when it is time to ask. */
    fun onAutoAdvance(now: Long): Boolean {
        if (!enabled || asking || stopped) return false
        autoEpisodes++
        if (autoEpisodes < EPISODES) return false
        asking = true
        askedAt = now
        return true
    }

    /** "Keep watching" was chosen. */
    fun answered(now: Long) {
        asking = false
        lastInput = now
        autoEpisodes = 0
    }

    /** Called about once a second. [live] and [playing] describe what the player is doing now. */
    fun tick(now: Long, live: Boolean, playing: Boolean): Event {
        if (!enabled || stopped) return Event.None
        if (asking) {
            if (now - askedAt >= ANSWER_MS) {
                stopped = true
                return Event.Stop
            }
            return Event.None
        }
        if (live && playing && now - lastInput >= LIVE_IDLE_MS) {
            asking = true
            askedAt = now
            return Event.Ask
        }
        return Event.None
    }

    companion object {
        const val EPISODES = 3
        const val LIVE_IDLE_MS = 4 * 60 * 60_000L
        const val ANSWER_MS = 5 * 60_000L
    }
}
