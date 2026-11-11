package dev.relay.tv.player

/** The arithmetic behind pause and rewind on live TV, apart from the player so it can be tested. */
object TimeshiftMath {
    /** MPEG-TS packets are 188 bytes; a seek has to start on a packet boundary. */
    const val TS_PACKET = 188

    /** Byte position of what's on screen now, estimated from the recording's average bitrate. */
    fun playingByte(offset: Long, positionMs: Long, bytesPerSecond: Double): Long =
        offset + (positionMs / 1000.0 * bytesPerSecond).toLong()

    /** How many seconds the picture is behind the live edge (0 while the bitrate is unknown). */
    fun behindSec(written: Long, playingByte: Long, bytesPerSecond: Double): Int {
        if (bytesPerSecond <= 0) return 0
        return ((written - playingByte) / bytesPerSecond).toInt().coerceAtLeast(0)
    }

    /**
     * Where to start playing after skipping [deltaSec] seconds (negative is back): never past
     * two seconds short of the live edge, never before the oldest byte still kept.
     */
    fun seekTarget(playingByte: Long, deltaSec: Int, bytesPerSecond: Double, written: Long, firstAvailable: Long): Long {
        val liveEdge = written - (2 * bytesPerSecond).toLong()
        val target = (playingByte + (deltaSec * bytesPerSecond).toLong()).coerceAtMost(liveEdge).coerceAtLeast(firstAvailable)
        return target - target % TS_PACKET
    }

    /** The text over the video, or null when there is nothing to show (playing and within 3 s of live). */
    fun barText(behindSec: Int, paused: Boolean, tv: Boolean): String? {
        if (!paused && behindSec <= 3) return null
        val t = if (behindSec >= 3600) "%d:%02d:%02d".format(behindSec / 3600, behindSec / 60 % 60, behindSec % 60)
        else "%d:%02d".format(behindSec / 60, behindSec % 60)
        val hints = if (tv) "   ◀ −10s   ▶ +30s" + (if (paused) "   OK play" else "") else ""
        return (if (paused) "⏸  Paused · " else "") + "$t behind live$hints"
    }
}
