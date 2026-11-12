package dev.relay.tv.data

/** Text matching for Search, written without regex or per-name allocations: it runs over every title in the library. */
object SearchText {
    /** Lower case, with every run of anything that isn't a letter or digit turned into one space, and trimmed. */
    fun norm(s: String): String {
        val lower = s.lowercase()
        val out = StringBuilder(lower.length)
        var gap = false
        var i = 0
        while (i < lower.length) {
            val cp = lower.codePointAt(i)
            i += Character.charCount(cp)
            if (isWordChar(cp)) {
                if (gap && out.isNotEmpty()) out.append(' ')
                gap = false
                out.appendCodePoint(cp)
            } else {
                gap = true
            }
        }
        return out.toString()
    }

    // The same characters as the regex classes \p{L} and \p{N}.
    private fun isWordChar(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
        Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER,
        Character.MODIFIER_LETTER, Character.OTHER_LETTER,
        Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER,
        -> true
        else -> false
    }

    /**
     * Token match on normalized names, with a one-typo fallback for single-word queries of five or
     * more letters. Null for a query with nothing to match (empty, or a single character).
     */
    fun matcher(query: String): ((String) -> Boolean)? {
        val normalized = matcherNormalized(query) ?: return null
        return { name -> normalized(norm(name)) }
    }

    /**
     * The same test as [matcher], for names that have already been through [norm]. Search keeps the
     * library's names in that form (see [NormalizedNames]) so a query doesn't redo it for every title.
     */
    fun matcherNormalized(query: String): ((String) -> Boolean)? {
        val tokens = norm(query).split(' ').filter { it.isNotEmpty() }
        if (tokens.isEmpty() || tokens.sumOf { it.length } < 2) return null
        val typo = if (tokens.size == 1 && tokens[0].length >= 5) tokens[0] else null
        return { n -> tokens.all { t -> n.contains(t) } || (typo != null && anyWordOneEditFrom(n, typo)) }
    }

    /** True when some space-separated word of [n] is one edit away from [target]. */
    private fun anyWordOneEditFrom(n: String, target: String): Boolean {
        var start = 0
        while (start <= n.length) {
            var end = n.indexOf(' ', start)
            if (end < 0) end = n.length
            if (end - start in (target.length - 1)..(target.length + 1) && oneEdit(n, start, end, target)) return true
            start = end + 1
        }
        return false
    }

    fun oneEdit(a: String, b: String): Boolean = oneEdit(a, 0, a.length, b)

    private fun oneEdit(a: String, from: Int, to: Int, b: String): Boolean {
        val alen = to - from
        if (kotlin.math.abs(alen - b.length) > 1) return false
        var i = 0
        var j = 0
        var edits = 0
        while (i < alen && j < b.length) {
            if (a[from + i] == b[j]) {
                i++; j++
            } else {
                if (++edits > 1) return false
                when {
                    alen > b.length -> i++
                    alen < b.length -> j++
                    else -> { i++; j++ }
                }
            }
        }
        return edits + (alen - i) + (b.length - j) <= 1
    }
}

/**
 * The names of one library list in the form Search compares ([SearchText.norm]), worked out the first
 * time the list is searched and kept until the list is replaced. Typing a second letter then costs a
 * scan of ready text instead of preparing a quarter of a million names again.
 */
class NormalizedNames<T>(private val name: (T) -> String) {
    private class Held(val list: List<*>, val names: Array<String>)

    @Volatile private var held: Held? = null

    /** One entry per item of [list], in the same order. Safe to call from any thread. */
    fun of(list: List<T>): Array<String> {
        held?.let { if (it.list === list) return it.names }
        return synchronized(this) {
            held?.let { if (it.list === list) return@synchronized it.names }
            val names = Array(list.size) { i -> SearchText.norm(name(list[i])) }
            held = Held(list, names)
            names
        }
    }

    fun clear() {
        held = null
    }
}
