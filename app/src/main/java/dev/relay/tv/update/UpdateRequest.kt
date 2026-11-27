package dev.relay.tv.update

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * What the updater sends to GitHub. Pure, so the rules can be tested.
 *
 * The repository is public, so no token is needed to read its releases, and a build made without one sends
 * none: GitHub answers 401 to an invalid token even for a public repository, so sending a stale one would
 * stop updates for no reason. A build made with a token (for a private copy of the repository) still sends it.
 */
object UpdateRequest {
    data class ReleaseCandidate(
        val versionCode: Int,
        val versionName: String,
        val notes: String,
        val assetUrl: String,
        val size: Long,
        val draft: Boolean = false,
        val prerelease: Boolean = false,
    )

    private val TRUSTED_HOSTS = setOf(
        "api.github.com",
        "github.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com",
    )

    /** The Authorization header value, or null when there is no token to send or the host isn't GitHub's API. */
    fun authHeader(token: String, host: String = "api.github.com"): String? {
        if (!host.equals("api.github.com", ignoreCase = true)) return null
        return token.trim().takeIf { it.isNotEmpty() }?.let { "Bearer $it" }
    }

    /** True only for HTTPS addresses on GitHub's release and asset hosts. */
    fun isTrustedUrl(url: String?): Boolean {
        val parsed = url?.toHttpUrlOrNull() ?: return false
        return parsed.isHttps && parsed.host.lowercase() in TRUSTED_HOSTS
    }

    /**
     * Where to download the APK from. With a token the API address is used (the only one a private repository
     * serves); without one, the plain download address, which has no request limit.
     */
    fun assetUrl(token: String, apiUrl: String?, downloadUrl: String?): String? =
        if (token.isBlank()) downloadUrl ?: apiUrl else apiUrl ?: downloadUrl

    /** Extracts the numeric build code from tags like "v1.0.140" or "v1.0.140-beta". */
    fun parseVersionCode(tag: String): Int? =
        tag.trim().removePrefix("v").substringBefore('-').substringAfterLast('.', "").toIntOrNull()

    /** Chooses the highest-version non-draft release allowed on this channel. */
    fun pickRelease(candidates: List<ReleaseCandidate>, includePrereleases: Boolean): ReleaseCandidate? =
        candidates
            .filter { !it.draft && (includePrereleases || !it.prerelease) }
            .maxByOrNull { it.versionCode }
}
