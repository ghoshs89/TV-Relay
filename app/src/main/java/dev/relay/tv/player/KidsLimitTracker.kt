package dev.relay.tv.player

import dev.relay.tv.data.KidsLimits

/**
 * Counts the time a kids profile actually spends playing and stops it at the daily limit or
 * bedtime. Called about once a second while the player is open; the Android side (views, the
 * player) is reached only through the callbacks, so the timing rules can be tested.
 */
class KidsLimitTracker(
    /** False for grown-up profiles: nothing is counted or enforced. */
    private val enabled: Boolean,
    private val addUsage: (Long) -> Unit,
    private val verdict: () -> KidsLimits.Verdict,
    /** A short heads-up, shown once, when five minutes or less are left. */
    private val warn: (String) -> Unit,
    /** The limit was reached; playback should stop and say why. Called once. */
    private val onLimit: (String) -> Unit,
) {
    var reached = false
        private set
    private var since = 0L
    private var pending = 0L
    private var warned = false

    /** [now] is a monotonic clock in ms and [playing] whether video is actually playing right now. */
    fun tick(now: Long, playing: Boolean) {
        if (!enabled || reached) return
        if (playing && since > 0) pending += now - since
        since = if (playing) now else 0L
        if (pending < FLUSH_MS) return
        flush()
        when (val v = verdict()) {
            is KidsLimits.Verdict.Blocked -> {
                reached = true
                onLimit(v.reason)
            }
            is KidsLimits.Verdict.Allowed -> {
                val left = v.minutesLeft
                if (left != null && left <= WARN_MINUTES && !warned) {
                    warned = true
                    warn(if (left <= 1) "About a minute of watching left" else "$left minutes of watching left")
                }
            }
        }
    }

    /** Saves the time counted so far (also when the player leaves the screen). */
    fun flush() {
        if (pending > 0) addUsage(pending)
        pending = 0
    }

    /** Playback stopped being watched (the app went to the background); the next tick starts a new stretch. */
    fun pause() {
        since = 0L
    }

    companion object {
        /** Usage is saved and the limit checked every 30 seconds of playing. */
        const val FLUSH_MS = 30_000L
        const val WARN_MINUTES = 5
    }
}
