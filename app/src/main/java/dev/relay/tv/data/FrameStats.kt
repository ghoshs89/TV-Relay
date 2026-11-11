package dev.relay.tv.data

/** Collects how long frames took to draw, and sums them up for the log. */
class FrameStats {
    private val ms = ArrayList<Float>()

    class Summary(
        val frames: Int,
        val over16: Int,
        val over32: Int,
        val over100: Int,
        val p95: Float,
        val worst: Float,
    ) {
        /** One log line. */
        fun text(label: String): String =
            "[$label] $frames frames, $over16 over 16 ms, $over32 over 32 ms, $over100 over 100 ms, " +
                "95% under ${"%.1f".format(java.util.Locale.US, p95)} ms, worst ${"%.1f".format(java.util.Locale.US, worst)} ms"
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
        )
        ms.clear()
        return s
    }
}
