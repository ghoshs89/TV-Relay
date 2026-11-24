package dev.relay.tv.ui

import dev.relay.tv.data.EpgEntry

internal data class GuideWindow(val start: Long, val end: Long) {
    fun shows(entry: EpgEntry): Boolean = minOf(entry.end, end) - maxOf(entry.start, start) >= 60

    fun expired(entry: EpgEntry): Boolean = entry.start < start && !shows(entry)

    companion object {
        fun at(now: Long): GuideWindow {
            val start = now / 1800 * 1800 - 3 * 3600
            return GuideWindow(start, start + 11 * 3600)
        }
    }
}
