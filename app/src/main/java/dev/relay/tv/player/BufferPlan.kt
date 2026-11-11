package dev.relay.tv.player

/**
 * How much ExoPlayer buffers before it starts and after it stalls. Live channels start sooner so
 * changing channel is quicker; a live stream has little to read ahead anyway. Movies keep the
 * safer start. Pure, so the numbers are in one tested place.
 */
data class BufferPlan(
    val minMs: Int,
    val maxMs: Int,
    val startMs: Int,
    val afterStallMs: Int,
) {
    companion object {
        fun forLive(live: Boolean): BufferPlan =
            if (live) BufferPlan(15_000, 60_000, 1_000, 3_000) else BufferPlan(15_000, 60_000, 2_000, 4_000)
    }
}
