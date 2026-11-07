package dev.relay.tv.data

/**
 * Viewing limits for kids profiles: minutes per day and quiet hours (bedtime to wake-up).
 * A parent can allow extra time, which lifts both limits until it runs out. Pure, so it is
 * unit tested; the app passes in today's usage and the clock.
 */
object KidsLimits {

    sealed interface Verdict {
        /** [minutesLeft] is null when no limit applies right now. */
        data class Allowed(val minutesLeft: Int?) : Verdict
        data class Blocked(val reason: String) : Verdict
    }

    val DAILY_CHOICES = listOf(0, 30, 60, 90, 120, 180)
    val BEDTIME_CHOICES = listOf("", "19:00", "19:30", "20:00", "20:30", "21:00", "21:30", "22:00")
    val WAKE_CHOICES = listOf("06:00", "06:30", "07:00", "07:30", "08:00", "09:00")
    const val DEFAULT_WAKE = "07:00"
    const val EXTRA_MINUTES = 30

    /** "20:30" as minutes after midnight; null when blank or malformed. */
    fun minutesOf(hhmm: String): Int? {
        val m = Regex("^(\\d{1,2}):(\\d{2})$").find(hhmm.trim()) ?: return null
        val h = m.groupValues[1].toInt()
        val mm = m.groupValues[2].toInt()
        return if (h in 0..23 && mm in 0..59) h * 60 + mm else null
    }

    /** True between bedtime and wake-up, across midnight when bedtime is later than wake-up. */
    fun quietNow(nowMin: Int, bedtime: String, wakeTime: String): Boolean {
        val bed = minutesOf(bedtime) ?: return false
        val wake = minutesOf(wakeTime) ?: minutesOf(DEFAULT_WAKE)!!
        if (bed == wake) return false
        return if (bed > wake) nowMin >= bed || nowMin < wake else nowMin in bed until wake
    }

    /**
     * @param usedMs watched today on this profile
     * @param nowMin minutes after local midnight
     * @param allowUntil a parent's extra time runs until this moment (epoch ms)
     */
    fun check(
        kids: Boolean,
        dailyLimitMin: Int,
        bedtime: String,
        wakeTime: String,
        usedMs: Long,
        nowMin: Int,
        nowMs: Long,
        allowUntil: Long,
    ): Verdict {
        if (!kids) return Verdict.Allowed(null)
        if (nowMs < allowUntil) return Verdict.Allowed(((allowUntil - nowMs) / 60_000).toInt())
        if (quietNow(nowMin, bedtime, wakeTime)) return Verdict.Blocked("It's past bedtime. Watching opens again at ${wakeTime.ifBlank { DEFAULT_WAKE }}.")
        if (dailyLimitMin > 0) {
            val left = dailyLimitMin - (usedMs / 60_000).toInt()
            if (left <= 0) return Verdict.Blocked("That's all the watching for today.")
            val toBed = minutesOf(bedtime)?.let { b -> if (b > nowMin) b - nowMin else null }
            return Verdict.Allowed(listOfNotNull(left, toBed).min())
        }
        val toBed = minutesOf(bedtime)?.let { b -> if (b > nowMin) b - nowMin else null }
        return Verdict.Allowed(toBed)
    }

    fun describe(dailyLimitMin: Int, bedtime: String, wakeTime: String): String {
        val parts = buildList {
            add(if (dailyLimitMin > 0) "${label(dailyLimitMin)} a day" else "No daily limit")
            if (minutesOf(bedtime) != null) add("bedtime $bedtime to ${wakeTime.ifBlank { DEFAULT_WAKE }}")
        }
        return parts.joinToString(" · ")
    }

    fun label(minutes: Int): String = when {
        minutes <= 0 -> "Off"
        minutes % 60 == 0 -> "${minutes / 60} h"
        minutes > 60 -> "${minutes / 60} h ${minutes % 60} min"
        else -> "$minutes min"
    }

    fun <T> next(choices: List<T>, current: T): T = choices[(choices.indexOf(current) + 1).mod(choices.size)]
}
