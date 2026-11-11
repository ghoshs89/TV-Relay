package dev.relay.tv.player

/**
 * Decides what the pause-buffer recorder does after each connection to the provider ends.
 *
 * A clean close is not trusted: providers that are overloaded often answer 200 and close the
 * stream immediately. So a connection that delivered little data, or one of several quick
 * disconnects, counts as a failure and moves to the next server. After three full rounds of
 * all servers the recorder gives up and the player falls back to direct playback.
 */
class RecorderPolicy(private val hostCount: Int, private val clock: () -> Long) {

    sealed interface Decision {
        /** Reconnect to the same server. */
        data object Retry : Decision

        /** Move to the next server. */
        data object NextHost : Decision

        data class GiveUp(val reason: String) : Decision
    }

    var host = 0
        private set
    var failures = 0
        private set
    private val quickEnds = ArrayDeque<Long>()

    /**
     * Called when a connection ends. [bytes] received on it, [durationMs] it lasted, and whether
     * it ended with an error (HTTP error, timeout, reset) rather than a clean close.
     */
    fun onEnded(bytes: Long, durationMs: Long, error: Boolean): Decision {
        val healthy = bytes >= MIN_GOOD_BYTES && durationMs >= HEALTHY_MS
        if (healthy) {
            // A long, productive connection: earlier trouble is forgotten.
            failures = 0
            quickEnds.clear()
        }
        val failed = when {
            error || bytes < MIN_GOOD_BYTES -> true
            healthy -> false
            else -> {
                // Real data but a short connection: tolerate a few, not a stream of them.
                val now = clock()
                quickEnds.addLast(now)
                while (quickEnds.isNotEmpty() && now - quickEnds.first() > QUICK_WINDOW_MS) quickEnds.removeFirst()
                if (quickEnds.size >= QUICK_LIMIT) {
                    quickEnds.clear()
                    true
                } else {
                    false
                }
            }
        }
        if (!failed) return Decision.Retry
        failures++
        if (failures >= hostCount.coerceAtLeast(1) * ROUNDS) {
            return Decision.GiveUp("No server delivered the stream after $failures attempts")
        }
        host = (host + 1) % hostCount.coerceAtLeast(1)
        return Decision.NextHost
    }

    companion object {
        const val MIN_GOOD_BYTES = 1024L * 1024
        const val HEALTHY_MS = 30_000L
        const val QUICK_WINDOW_MS = 30_000L
        const val QUICK_LIMIT = 3
        const val ROUNDS = 3

        /** No data this long after connecting counts as a failed connection. */
        const val FIRST_DATA_TIMEOUT_MS = 12_000L
    }
}

/**
 * Notices playback stuck buffering. [tick] is called about once a second; it returns true once
 * buffering has lasted [limitMs] (and then starts counting again). Paused playback never counts.
 */
class StallWatchdog(private val limitMs: Long, private val clock: () -> Long) {
    private var since = 0L

    fun tick(buffering: Boolean): Boolean {
        if (!buffering) {
            since = 0L
            return false
        }
        val now = clock()
        if (since == 0L) {
            since = now
            return false
        }
        if (now - since >= limitMs) {
            since = 0L
            return true
        }
        return false
    }

    fun reset() {
        since = 0L
    }

    companion object {
        const val LIVE_MS = 20_000L
        const val VOD_MS = 30_000L

        /** Live reconnects before giving up and showing Retry / Next channel. */
        const val LIVE_TRIES = 3
    }
}

/** The pause buffer records raw MPEG-TS; HLS playlists must play directly. */
object TimeshiftSupport {
    fun supports(urls: List<String>): Boolean =
        urls.isNotEmpty() && urls.none { isHls(it) }

    fun isHls(url: String): Boolean {
        val path = url.substringBefore('?').lowercase()
        return path.endsWith(".m3u8") || path.endsWith(".m3u")
    }

    /** True when the first bytes of a response are a playlist rather than a transport stream. */
    fun looksLikePlaylist(head: ByteArray, n: Int): Boolean {
        val s = String(head, 0, minOf(n, 16), Charsets.ISO_8859_1).trimStart('\uFEFF', ' ', '\r', '\n')
        return s.startsWith("#EXTM3U")
    }
}
