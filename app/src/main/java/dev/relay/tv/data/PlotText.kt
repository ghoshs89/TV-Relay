package dev.relay.tv.data

/**
 * Tidies a title's summary as providers send it. Many copy their summaries from IMDb or similar sites,
 * which cut the text and add a link such as "... Read all" or "See full summary »". In Relay that is
 * only text that looks like a link, so it is removed. A summary that was cut keeps a "…" at the end.
 */
object PlotText {
    private val TAIL = Regex(
        """(?i)(\s*(?:\.{2,}|…))?[\s»>|\-]*\b(?:read\s+all|read\s+more|see\s+full\s+(?:plot\s+)?summary|see\s+full\s+synopsis|full\s+summary)\b[\s»>….]*$""",
    )

    fun clean(raw: String?): String? {
        var s = raw?.trim() ?: return null
        // Some providers repeat the link (once from the site, once from their own tools).
        repeat(3) {
            val m = TAIL.find(s) ?: return@repeat
            val cut = m.groups[1] != null
            s = s.substring(0, m.range.first).trimEnd()
            if (cut && s.isNotEmpty() && s.last() !in ".!?…") s += "…"
        }
        return s.trim().ifEmpty { null }
    }
}
