package dev.relay.tv.data

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Notices when the screen thread stops responding (the app looks frozen and Android may offer
 * to close it) and logs what that thread was doing, which a frozen app can't do for itself.
 */
object StallWatch {
    private const val CHECK_MS = 1_000L
    private const val STALL_MS = 4_000L

    @Volatile private var lastBeat = SystemClock.uptimeMillis()
    @Volatile private var started = false

    fun start() {
        if (started) return
        started = true
        val main = Handler(Looper.getMainLooper())
        val beat = Runnable { lastBeat = SystemClock.uptimeMillis() }
        Thread({
            var stalledSince = 0L
            var reported = false
            var prevLoopAt = SystemClock.uptimeMillis()
            while (true) {
                try {
                    main.post(beat)
                    Thread.sleep(CHECK_MS)
                    val now = SystemClock.uptimeMillis()
                    val verdict = StallVerdict.verdict(prevLoopAt, now, lastBeat, STALL_MS, CHECK_MS)
                    prevLoopAt = now
                    when (verdict) {
                        is StallVerdict.Verdict.ProcessPaused -> {
                            // Android froze the whole app (cached, in the background); nobody saw a stall.
                            lastBeat = now
                            stalledSince = 0L
                            reported = false
                        }
                        is StallVerdict.Verdict.Stalled -> {
                            if (stalledSince == 0L) stalledSince = lastBeat
                            if (!reported) {
                                reported = true
                                AppLog.w("Stall", "Screen thread not responding for ${verdict.ms / 1000}s; it is in:\n${mainStack()}")
                            }
                        }
                        is StallVerdict.Verdict.Ok -> if (stalledSince != 0L) {
                            AppLog.w("Stall", "Screen thread responded again after ${(now - stalledSince) / 1000}s")
                            stalledSince = 0L
                            reported = false
                        }
                    }
                } catch (_: InterruptedException) {
                    return@Thread
                } catch (_: Throwable) {
                    // Watching must never break the app.
                }
            }
        }, "relay-stall-watch").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    /** The screen thread's current stack: Relay's frames plus the top few framework frames. */
    private fun mainStack(): String {
        val frames = Looper.getMainLooper().thread.stackTrace
        val own = frames.filter { it.className.startsWith("dev.relay") }.take(12)
        return (frames.take(6) + own).distinct().joinToString("\n") { "    at $it" }
    }
}
