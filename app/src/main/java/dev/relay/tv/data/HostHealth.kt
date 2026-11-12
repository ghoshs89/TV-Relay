package dev.relay.tv.data

/** What is known about one server since this start of the app. Times are [HostHealth]'s clock. */
data class HostStatus(
    val host: String,
    val lastOkAt: Long?,
    val lastOkMs: Long?,
    val lastFailAt: Long?,
    val lastError: String?,
    val coolingUntil: Long?,
)

/**
 * Which servers answered and which failed, in memory only (a fresh start forgets it). A server that
 * fails is tried last for [COOL_MS] unless it answers again. Fed by API calls only: one channel not
 * playing says little about a whole server. Pure, so it is unit tested on a computer.
 */
class HostHealth(val clock: () -> Long) {
    private val map = HashMap<String, HostStatus>()

    @Synchronized
    fun answered(host: String, ms: Long) {
        val s = map[host] ?: blank(host)
        map[host] = s.copy(lastOkAt = clock(), lastOkMs = ms, coolingUntil = null)
    }

    @Synchronized
    fun failed(host: String, error: Throwable?) {
        val now = clock()
        val s = map[host] ?: blank(host)
        map[host] = s.copy(lastFailAt = now, lastError = describe(error), coolingUntil = now + COOL_MS)
    }

    @Synchronized
    fun coolingUntil(): Map<String, Long> =
        map.values.mapNotNull { s -> s.coolingUntil?.let { s.host to it } }.toMap()

    /** One entry per server in [hosts], in that order, known or not. */
    @Synchronized
    fun snapshot(hosts: List<String>): List<HostStatus> = hosts.map { map[it] ?: blank(it) }

    @Synchronized
    fun forget() = map.clear()

    private fun blank(host: String) = HostStatus(host, null, null, null, null, null)

    companion object {
        const val COOL_MS = 10 * 60_000L

        /** A short reason for the log and Settings: the exception's message, cut to one line. */
        fun describe(e: Throwable?): String {
            if (e == null) return "failed"
            val m = (e.message ?: e.javaClass.simpleName).lineSequence().first().trim()
            return if (m.length > 60) m.take(59) + "…" else m
        }
    }
}
