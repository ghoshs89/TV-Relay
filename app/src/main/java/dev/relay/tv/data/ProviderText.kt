package dev.relay.tv.data

import android.net.Uri

/**
 * Pulls server URLs, username and password out of a provider's welcome message, e.g.
 *   "url/host = http://example.com  ...  username = abc  password = xyz"
 * Links with a path (setup guides, DNS checkers) are ignored; only bare server hosts count.
 */
object ProviderText {
    /** [nets]: the network each server is meant for, read from the words in front of it (see [HostLabels]). */
    data class Result(
        val hosts: List<String>,
        val username: String?,
        val password: String?,
        val nets: Map<String, HostNet> = emptyMap(),
    )

    private val URL = Regex("""https?://[^\s"'<>|,()\[\]]+""", RegexOption.IGNORE_CASE)
    private val USER = Regex("""(?i)\buser(?:name)?\b\s*[:=]\s*([^\s,;|]+)""")
    private val PASS = Regex("""(?i)\bpass(?:word)?\b\s*[:=]\s*([^\s,;|]+)""")

    fun parse(text: String): Result {
        if (text.isBlank()) return Result(emptyList(), null, null)
        var user: String? = null
        var pass: String? = null
        val hosts = mutableListOf<String>()
        val labels = mutableListOf<String>()
        var labelStart = 0

        for (m in URL.findAll(text)) {
            val label = text.substring(labelStart, m.range.first)
            labelStart = m.range.last + 1
            val raw = m.value.trimEnd('.', '/')
            val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: continue
            val path = uri.path.orEmpty().trim('/')
            val isApi = path.endsWith("player_api.php") || path.endsWith("get.php")
            if (isApi) {
                // M3U / API links carry the login in the query string.
                if (user == null) user = uri.getQueryParameter("username")
                if (pass == null) pass = uri.getQueryParameter("password")
            } else if (path.isNotEmpty()) {
                continue // a web page, not a server
            }
            val host = XtreamApi.normalizeHost(raw)
            if (host.isNotEmpty() && host !in hosts) {
                hosts += host
                labels += label
            }
        }
        USER.find(text)?.let { user = it.groupValues[1] }
        PASS.find(text)?.let { pass = it.groupValues[1] }
        val tags = HostLabels.tags(labels)
        return Result(hosts, user, pass, hosts.indices.associate { hosts[it] to tags[it] })
    }
}
