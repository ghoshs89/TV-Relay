package dev.relay.tv.data

/**
 * How hard Relay may lean on the device it is running on. Decided once at start from what the device
 * is, and read wherever a wait or a limit used to be one fixed number chosen for a slow TV.
 *
 * Two rules keep this safe. A knob only changes *when* something happens, never what is shown. And
 * [STANDARD] holds exactly the numbers every device used before tiers existed (a test pins each one),
 * so a TV behaves as it always did; only [FAST] devices, in practice recent phones, get the shorter
 * waits and wider limits.
 */
data class DeviceTier(
    val name: String,
    /** Big library lists read or parsed at the same time. */
    val parallelLists: Int,
    /** How long a channel row waits before asking for its guide (rows passed while scrolling never ask). */
    val rowGuideDelayMs: Long,
    /** The same for a row of the guide grid. */
    val gridRowDelayMs: Long,
    /** Guide requests to the provider at once: for list rows, and for the guide grid. */
    val rowGuideRequests: Int,
    val gridGuideRequests: Int,
    /** Pause after the last letter before Find in Live TV and Search start looking. */
    val findPauseMs: Long,
    val searchPauseMs: Long,
    /** Image downloads at once, in all and per server. */
    val imageRequests: Int,
    val imageRequestsPerHost: Int,
    /** Posters fade in when they load. Off where a fade per poster costs frames while scrolling. */
    val posterFade: Boolean,
    /** How long an overdue library waits after start-up before it is refreshed. */
    val refreshStartDelayMs: Long,
    /** How long after a library is prepared its ready-to-use copy is written. */
    val snapshotDelayMs: Long,
    /** Touch targets get at least this height in dp (0 = as drawn). Remote-driven screens don't need it. */
    val minTouchDp: Int,
    /** One display frame, in milliseconds; a frame that takes more than two of these is logged as slow. */
    val framePeriodMs: Float,
) {
    val fast: Boolean get() = name == FAST_NAME

    /** One line for the top of the log, so a log says which numbers were in force. */
    fun describe(): String =
        "Device tier: $name (lists at once $parallelLists, guide wait $rowGuideDelayMs/$gridRowDelayMs ms, " +
            "guide requests $rowGuideRequests/$gridGuideRequests, typing pause $findPauseMs/$searchPauseMs ms, " +
            "images $imageRequests/$imageRequestsPerHost, refresh after ${refreshStartDelayMs / 1000} s, " +
            "frame ${"%.1f".format(java.util.Locale.US, framePeriodMs)} ms)"

    companion object {
        private const val FAST_NAME = "fast"

        /** What every device did before tiers: tuned for a TV with four slow cores. [cores] only matters below four. */
        fun standard(cores: Int = 4, refreshHz: Float = 60f, touch: Boolean = false) = DeviceTier(
            name = "standard",
            parallelLists = if (cores >= 4) 3 else 2,
            rowGuideDelayMs = 350, gridRowDelayMs = 120,
            rowGuideRequests = 3, gridGuideRequests = 4,
            findPauseMs = 250, searchPauseMs = 350,
            imageRequests = 8, imageRequestsPerHost = 4,
            posterFade = false,
            refreshStartDelayMs = 20_000, snapshotDelayMs = 5_000,
            minTouchDp = if (touch) MIN_TOUCH_DP else 0,
            framePeriodMs = period(refreshHz),
        )

        val STANDARD = standard()

        fun fast(cores: Int, refreshHz: Float) = DeviceTier(
            name = FAST_NAME,
            // Two cores stay free for the screen and playback.
            parallelLists = (cores - 2).coerceIn(3, 6),
            rowGuideDelayMs = 100, gridRowDelayMs = 40,
            rowGuideRequests = 6, gridGuideRequests = 8,
            findPauseMs = 120, searchPauseMs = 150,
            imageRequests = 16, imageRequestsPerHost = 6,
            posterFade = true,
            refreshStartDelayMs = 5_000, snapshotDelayMs = 2_000,
            minTouchDp = MIN_TOUCH_DP,
            framePeriodMs = period(refreshHz),
        )

        const val MIN_TOUCH_DP = 48

        /**
         * The tier for a device. Fast needs all of: not a TV, not marked low-memory by Android, six or more
         * cores, and an app memory limit of at least 256 MB. Anything else, and anything unknown, is standard.
         */
        fun of(cores: Int, memoryLimitMb: Long, lowRam: Boolean, tv: Boolean, refreshHz: Float): DeviceTier =
            if (!tv && !lowRam && cores >= 6 && memoryLimitMb >= 256) fast(cores, refreshHz)
            else standard(cores, refreshHz, touch = !tv)

        private fun period(refreshHz: Float): Float = if (refreshHz in 24f..360f) 1000f / refreshHz else 1000f / 60f

        /** Set once by the app at start; tests and anything that runs before that see the standard tier. */
        @Volatile var current: DeviceTier = STANDARD
    }
}
