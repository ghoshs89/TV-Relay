package dev.relay.tv.data

/**
 * Kids' viewing time for one profile on one day. Each screen keeps its own total as "date|milliseconds"
 * and knows the others' from sync; the limit applies to the sum, so 60 minutes a day means 60 minutes
 * across every TV in the house. Pure, so the sum and the day roll-over can be tested.
 */
object DailyUsage {

    /** Milliseconds in [raw] if it is for [today], otherwise 0. */
    fun ms(raw: String?, today: String): Long {
        if (raw == null || raw.substringBefore('|') != today) return 0L
        return raw.substringAfter('|').toLongOrNull()?.coerceAtLeast(0L) ?: 0L
    }

    fun format(today: String, ms: Long): String = "$today|$ms"

    /** This screen's time plus every other screen's, for [today]. */
    fun total(own: String?, others: Map<String, String>, today: String): Long =
        ms(own, today) + others.values.sumOf { ms(it, today) }

    /** The other screens' entries that are for [today]; older days are dropped. */
    fun todayOnly(others: Map<String, String>, today: String): Map<String, String> =
        others.filterValues { it.substringBefore('|') == today }
}
