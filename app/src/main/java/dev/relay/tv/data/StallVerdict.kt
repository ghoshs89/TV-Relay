package dev.relay.tv.data

/**
 * Decides what the stall watcher's latest check means. Pure, so it can be tested: the watcher
 * thread is frozen together with the rest of the process when Android puts a cached app to sleep,
 * and on waking it used to compare against a heartbeat from two days earlier and report a
 * "freeze" of 110,514 seconds that nobody saw.
 */
object StallVerdict {

    sealed interface Verdict {
        /** The screen thread answered recently. */
        object Ok : Verdict

        /** The screen thread has not answered for [ms] while the watcher itself kept running. */
        data class Stalled(val ms: Long) : Verdict

        /** The watcher itself did not run for a long time: the whole process was paused. Nothing to report. */
        object ProcessPaused : Verdict
    }

    /**
     * [prevLoopAt]: when the watcher's previous check ran. [now]: this check. [lastBeat]: when the
     * screen thread last answered. All in the same clock (uptime, milliseconds).
     */
    fun verdict(prevLoopAt: Long, now: Long, lastBeat: Long, stallMs: Long, checkMs: Long): Verdict {
        // The watcher sleeps checkMs between checks; if far longer passed, it was frozen too.
        if (now - prevLoopAt > stallMs + checkMs) return Verdict.ProcessPaused
        val late = now - lastBeat
        return if (late >= stallMs) Verdict.Stalled(late) else Verdict.Ok
    }
}
