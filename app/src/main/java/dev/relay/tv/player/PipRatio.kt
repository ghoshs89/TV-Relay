package dev.relay.tv.player

/** The aspect ratio for picture-in-picture, which Android only accepts between 1:2.39 and 2.39:1. */
object PipRatio {
    /** Returns (width, height) for a video of this size; 16:9 until the size is known. */
    fun safe(videoWidth: Int, videoHeight: Int): Pair<Int, Int> {
        val known = videoWidth > 0 && videoHeight > 0
        val w = if (known) videoWidth else 16
        val h = if (known) videoHeight else 9
        val ratio = w.toFloat() / h
        return when {
            ratio > 2.39f -> 239 to 100
            ratio < 0.42f -> 100 to 239
            else -> w to h
        }
    }
}
