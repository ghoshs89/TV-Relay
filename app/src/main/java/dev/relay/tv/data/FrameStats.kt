package dev.relay.tv.data

/** Collects how long frames took to draw, and sums them up for the log. */
class FrameStats(
    /**
     * One display frame in milliseconds (16.7 at 60 Hz, 8.3 at 120 Hz). A frame that takes more than two
     * of these was visibly late on this screen, which the fixed 16, 32 and 100 ms counts can't show on a
     * 90 or 120 Hz phone: there a 20 ms frame is two missed refreshes and is under every fixed line.
     */
    private val framePeriodMs: Float = 1000f / 60f,
) {
    private val ms = ArrayList<Float>()

    class Summary(
        val frames: Int,
        val over16: Int,
        val over32: Int,
        val over100: Int,
        val p95: Float,
        val worst: Float,
        /** Frames that took more than two display frames, and the length of one display frame. */
        val late: Int = over32,
        val framePeriodMs: Float = 1000f / 60f,
    ) {
        /** True when the screen refreshes faster than 60 times a second, so [late] says more than [over32]. */
        val highRefresh: Boolean get() = framePeriodMs < 16f

        /** One log line. */
        fun text(label: String): String =
            "[$label] $frames frames, $over16 over 16 ms, $over32 over 32 ms, $over100 over 100 ms, " +
                "95% under ${"%.1f".format(java.util.Locale.US, p95)} ms, worst ${"%.1f".format(java.util.Locale.US, worst)} ms" +
                // Added at the end so the benchmark report, which reads the line up to "worst", is not disturbed.
                if (highRefresh) ", $late late for a ${"%.0f".format(java.util.Locale.US, 1000f / framePeriodMs)} Hz screen" else ""
    }

    @Synchronized
    fun add(durationMs: Float) {
        ms += durationMs
    }

    @Synchronized
    fun isEmpty(): Boolean = ms.isEmpty()

    /** Sums up everything added since the last call and starts again. Null when there were no frames. */
    @Synchronized
    fun take(): Summary? {
        if (ms.isEmpty()) return null
        val sorted = ms.sorted()
        val s = Summary(
            frames = ms.size,
            over16 = ms.count { it > 16.7f },
            over32 = ms.count { it > 33.4f },
            over100 = ms.count { it > 100f },
            p95 = sorted[((sorted.size - 1) * 0.95).toInt()],
            worst = sorted.last(),
            late = ms.count { it > framePeriodMs * 2 },
            framePeriodMs = framePeriodMs,
        )
        ms.clear()
        return s
    }
}
