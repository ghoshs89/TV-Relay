package dev.relay.tv.sync

import dev.relay.tv.data.Jsons
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A GitHub repository as `owner/name`. */
data class Repo(val owner: String, val name: String) {
    override fun toString() = "$owner/$name"

    companion object {
        private val PART = Regex("[A-Za-z0-9._-]{1,100}")

        /** `owner/name`, or the repository's web address; anything else gives null. */
        fun parse(text: String): Repo? {
            var t = text.trim().trimEnd('/')
            for (p in listOf("https://github.com/", "http://github.com/", "github.com/", "git@github.com:")) {
                if (t.startsWith(p, ignoreCase = true)) {
                    t = t.substring(p.length)
                    break
                }
            }
            if (t.endsWith(".git", ignoreCase = true)) t = t.dropLast(4)
            val parts = t.split('/')
            if (parts.size != 2) return null
            val (o, n) = parts
            if (!PART.matches(o) || !PART.matches(n) || n == "." || n == ".." || o == "." || o == "..") return null
            return Repo(o, n)
        }
    }
}

/** Small base64 so nothing depends on the Android or Java version (java.util.Base64 needs Android 8). */
object Base64Lite {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun encode(data: ByteArray): String {
        val sb = StringBuilder((data.size + 2) / 3 * 4)
        var i = 0
        while (i < data.size) {
            val b0 = data[i].toInt() and 0xff
            val b1 = if (i + 1 < data.size) data[i + 1].toInt() and 0xff else 0
            val b2 = if (i + 2 < data.size) data[i + 2].toInt() and 0xff else 0
            sb.append(ALPHABET[b0 shr 2])
            sb.append(ALPHABET[((b0 and 3) shl 4) or (b1 shr 4)])
            sb.append(if (i + 1 < data.size) ALPHABET[((b1 and 15) shl 2) or (b2 shr 6)] else '=')
            sb.append(if (i + 2 < data.size) ALPHABET[b2 and 63] else '=')
            i += 3
        }
        return sb.toString()
    }

    /** Like [encode] with `-` and `_` instead of `+` and `/`, and no padding: safe in links and chat messages. */
    fun encodeUrl(data: ByteArray): String = encode(data).replace('+', '-').replace('/', '_').trimEnd('=')

    /** The bytes in [text] written by [encodeUrl] (or [encode]), or null if it is damaged. */
    fun decodeUrl(text: String): ByteArray? {
        val t = text.filter { !it.isWhitespace() }.replace('-', '+').replace('_', '/').trimEnd('=')
        if (t.length % 4 == 1) return null
        return decode(t + "=".repeat((4 - t.length % 4) % 4))
    }

    /** The bytes in [text] (line breaks and spaces are ignored), or null if it is not valid base64. */
    fun decode(text: String): ByteArray? {
        val clean = text.filter { !it.isWhitespace() }
        if (clean.length % 4 != 0) return null
        val out = java.io.ByteArrayOutputStream(clean.length / 4 * 3)
        var i = 0
        while (i < clean.length) {
            val q = IntArray(4)
            var pad = 0
            for (j in 0 until 4) {
                val c = clean[i + j]
                if (c == '=') {
                    if (i + 4 != clean.length || j < 2) return null
                    pad++
                    q[j] = 0
                } else {
                    if (pad > 0) return null
                    val v = ALPHABET.indexOf(c)
                    if (v < 0) return null
                    q[j] = v
                }
            }
            out.write((q[0] shl 2) or (q[1] shr 4))
            if (pad < 2) out.write(((q[1] and 15) shl 4) or (q[2] shr 2))
            if (pad < 1) out.write(((q[2] and 3) shl 6) or q[3])
            i += 4
        }
        return out.toByteArray()
    }
}

/**
 * What GitHub's contents API says, turned into results. Pure, so every status code can be tested
 * without a network. The file's `sha` is the version tag: GitHub refuses a write that names a sha
 * that is no longer current.
 */
object GitHubApi {
    const val FILE = "relay-sync.bin"
    private const val API = "https://api.github.com"

    fun contentsUrl(repo: Repo) = "$API/repos/${repo.owner}/${repo.name}/contents/$FILE"

    fun repoUrl(repo: Repo) = "$API/repos/${repo.owner}/${repo.name}"

    private fun message(body: String): String = try {
        ((Jsons.json.parseToJsonElement(body) as? JsonObject)?.get("message") as? JsonPrimitive)?.content.orEmpty()
    } catch (e: Exception) {
        ""
    }

    private fun failure(code: Int, body: String, rateLeft: String?): TransportException = when {
        code == 401 -> TransportException(Problem.Refused, "GitHub refused the sync token (it may have expired or been removed).")
        code == 403 && rateLeft == "0" -> TransportException(Problem.Other, "GitHub says too many requests; Relay will try again later.")
        code == 403 -> TransportException(Problem.Refused, "The sync token is not allowed to use this repository. It needs Contents: read and write.")
        code == 404 -> TransportException(Problem.NotFound, "GitHub can't find the repository, or the sync token can't see it.")
        code == 413 -> TransportException(Problem.TooLarge, "The sync file is too large for GitHub.")
        code in 500..599 -> TransportException(Problem.Offline, "GitHub is not answering (HTTP $code).")
        else -> TransportException(Problem.Other, "GitHub answered HTTP $code${message(body).takeIf { it.isNotEmpty() }?.let { ": $it" } ?: ""}.")
    }

    /**
     * The result of reading the file: null for "not there yet" (a 404 on the file), the file otherwise.
     * A 404 that really means "no such repository" shows up on the first write instead.
     */
    fun readResult(code: Int, body: String, rateLeft: String?): Remote? {
        if (code == 404) return null
        if (code != 200) throw failure(code, body, rateLeft)
        val o = try {
            Jsons.json.parseToJsonElement(body) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: throw TransportException(Problem.Other, "GitHub sent something Relay can't read.")
        val sha = (o["sha"] as? JsonPrimitive)?.content ?: throw TransportException(Problem.Other, "GitHub sent no version for the sync file.")
        val content = (o["content"] as? JsonPrimitive)?.content.orEmpty()
        if (content.isBlank()) {
            // Files over 1 MB come back without content.
            throw TransportException(Problem.TooLarge, "The sync file is too large to read from GitHub.")
        }
        val bytes = Base64Lite.decode(content) ?: throw TransportException(Problem.Other, "GitHub sent the sync file in a form Relay can't read.")
        return Remote(bytes, sha)
    }

    fun putBody(bytes: ByteArray, sha: String?, note: String): String = JsonObject(
        buildMap {
            put("message", JsonPrimitive(note))
            put("content", JsonPrimitive(Base64Lite.encode(bytes)))
            if (sha != null) put("sha", JsonPrimitive(sha))
        },
    ).toString()

    /** 409 and 422 mean the file changed since it was read (or was created meanwhile). */
    fun writeResult(code: Int, body: String, rateLeft: String?): WriteResult {
        if (code == 200 || code == 201) {
            val sha = try {
                (((Jsons.json.parseToJsonElement(body) as? JsonObject)?.get("content") as? JsonObject)?.get("sha") as? JsonPrimitive)?.content
            } catch (e: Exception) {
                null
            } ?: throw TransportException(Problem.Other, "GitHub saved the file but sent no version back.")
            return WriteResult.Saved(sha)
        }
        if (code == 409 || code == 422) return WriteResult.Changed
        throw failure(code, body, rateLeft)
    }

    /**
     * What to tell the viewer after asking GitHub about the repository when sync is being set up, or
     * null if it is fine: reachable, writable and private.
     */
    fun repoCheck(repo: Repo, code: Int, body: String, rateLeft: String?): String? {
        if (code != 200) return failure(code, body, rateLeft).message
        val o = try {
            Jsons.json.parseToJsonElement(body) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: return "GitHub sent something Relay can't read."
        val push = ((o["permissions"] as? JsonObject)?.get("push") as? JsonPrimitive)?.content
        val private = (o["private"] as? JsonPrimitive)?.content
        return when {
            push == "false" -> "The token can read $repo but not write to it. Give it Contents: read and write."
            private == "false" -> "$repo is a public repository. Use a private one: the sync file holds your history, even though it is encrypted."
            else -> null
        }
    }
}
