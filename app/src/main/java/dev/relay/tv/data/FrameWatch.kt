package dev.relay.tv.data

import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window
import dev.relay.tv.BuildConfig

/**
 * Notes how long frames take to draw, so slow scrolling shows up in the log with numbers. It only
 * records while "Save detailed logs" is on (and in the benchmark build), once a minute writes a
 * line if any frame was slow, and costs nothing when it is off beyond an empty callback.
 */
object FrameWatch {
    // Made on first use, after the app has worked out what kind of device this is.
    private val stats by lazy { FrameStats(DeviceTier.current.framePeriodMs) }
    private val thread by lazy { HandlerThread("relay-frames").apply { start() } }
    @Volatile private var started = false
    @Volatile var label: String = "browse"
        private set

    private val recording: Boolean get() = BuildConfig.BENCH || AppLog.saving

    fun attach(window: Window) {
        val handler = Handler(thread.looper)
        window.addOnFrameMetricsAvailableListener(
            { _, metrics, _ -> if (recording) stats.add(metrics.getMetric(FrameMetrics.TOTAL_DURATION) / 1_000_000f) },
            handler,
        )
        if (started) return
        started = true
        // The benchmark build reports every few seconds so each stretch of a scripted run shows up
        // on its own; everywhere else it is once a minute.
        val period = if (BuildConfig.BENCH) 4_000L else 60_000L
        val tick = object : Runnable {
            override fun run() {
                report(onlyIfSlow = !BuildConfig.BENCH)
                handler.postDelayed(this, period)
            }
        }
        handler.postDelayed(tick, period)
    }

    /** Writes the line for what was drawn since the last one, then starts a new stretch called [next]. */
    fun mark(next: String) {
        report(onlyIfSlow = false)
        label = next
    }

    private fun report(onlyIfSlow: Boolean) {
        val s = stats.take() ?: return
        val line = s.text(label)
        if (s.over32 > 0 || (s.highRefresh && s.late > 0) || !onlyIfSlow) AppLog.i("Frames", line) else AppLog.d("Frames", line)
    }
}
