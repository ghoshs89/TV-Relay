package dev.relay.tv.sync

/** Forgiving handling of what is pasted on the phone page for sync. Pure. */
object SyncInput {
    private val QUOTES = setOf('"', '\'', '`', '“', '”', '‘', '’', '«', '»', '<', '>')
    private val PREFIX = Regex("(?i)^(bearer|token)\\s+")

    /**
     * The token without quotes, spaces, line breaks and a leading `Bearer`/`token`; null if what is left
     * cannot be a GitHub token (they are letters, digits and underscores, and long).
     */
    fun token(raw: String): String? {
        val t = raw.filterNot { it in QUOTES }.trim().replace(PREFIX, "").filterNot(Char::isWhitespace)
        if (t.length < 30 || t.length > 255) return null
        if (!t.all { it.isLetterOrDigit() && it.code < 128 || it == '_' }) return null
        return t
    }

    /** What to tell the viewer when [repo] or [token] is no good, or null when both are. */
    fun problem(repo: String, token: String): String? = when {
        Repo.parse(repo) == null -> "Enter the repository as owner/name, for example me/relay-sync."
        token(token) == null -> "That doesn't look like a GitHub token. Copy the whole token, it starts with github_pat_."
        else -> null
    }
}
