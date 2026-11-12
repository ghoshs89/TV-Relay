package dev.relay.tv.sync

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The sync file kept in the owner's own private GitHub repository. The token is a fine-grained one
 * limited to that repository (Contents: read and write). It is held on this device like the provider
 * password, is masked in the log, and is never built into the app.
 */
class GitHubTransport(http: OkHttpClient, private val repo: Repo, private val token: String) : SyncTransport {

    private val client = http.newBuilder().callTimeout(40, TimeUnit.SECONDS).build()

    private fun request(url: String): Request.Builder = Request.Builder().url(url)
        .header("Authorization", "Bearer $token")
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")
        .header("User-Agent", "RelayTV-Sync")

    private fun <T> call(request: Request, handle: (code: Int, body: String, rateLeft: String?) -> T): T {
        try {
            client.newCall(request).execute().use { r ->
                return handle(r.code, r.body?.string().orEmpty(), r.header("x-ratelimit-remaining"))
            }
        } catch (e: TransportException) {
            throw e
        } catch (e: IOException) {
            throw TransportException(Problem.Offline, "Can't reach GitHub (${e.javaClass.simpleName}).")
        }
    }

    override fun read(): Remote? =
        call(request(GitHubApi.contentsUrl(repo)).get().build()) { code, body, left -> GitHubApi.readResult(code, body, left) }

    override fun write(bytes: ByteArray, ifVersion: String?): WriteResult {
        val body = GitHubApi.putBody(bytes, ifVersion, "Relay sync").toRequestBody("application/json".toMediaType())
        return call(request(GitHubApi.contentsUrl(repo)).put(body).build()) { code, text, left -> GitHubApi.writeResult(code, text, left) }
    }

    /**
     * Checks that the repository can be reached with this token, that the token may write to it and
     * that it is private. Returns null when all is well, otherwise a sentence for the viewer.
     */
    fun check(): String? = try {
        call(request(GitHubApi.repoUrl(repo)).get().build()) { code, body, left -> GitHubApi.repoCheck(repo, code, body, left) }
    } catch (e: TransportException) {
        e.message
    }
}
