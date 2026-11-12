package dev.relay.tv.data

/** How Settings → Servers and the log describe one server's state. Pure, so it is unit tested. */
object ServerText {
    fun ago(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "just now"
            s < 3600 -> "${s / 60} min ago"
            s < 86_400 -> "${s / 3600} h ago"
            else -> "${s / 86_400} d ago"
        }
    }

    fun status(st: HostStatus, now: Long): String {
        val cooling = st.coolingUntil?.let { it > now } == true
        val ok = st.lastOkAt?.let { "answered in ${st.lastOkMs ?: 0} ms ${ago(now - it)}" }
        val fail = st.lastFailAt?.let { "failed ${ago(now - it)}: ${st.lastError ?: "failed"}" }
        return when {
            cooling -> "skipped for ${((st.coolingUntil!! - now + 59_999) / 60_000)} min, $fail"
            ok != null && fail != null && st.lastFailAt!! > st.lastOkAt!! -> "$fail; before that $ok"
            ok != null -> ok
            fail != null -> fail
            else -> "not tried since Relay started"
        }
    }
}
