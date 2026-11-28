package dev.relay.tv.data

import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Xtream Codes client. Every call walks the account's host list starting with the last host
 * that answered, so a dead primary server silently falls through to the backups.
 */
class XtreamApi(
    private val http: OkHttpClient,
    private val userAgent: () -> String,
) {
    @Volatile var account: Account? = null
    @Volatile var host: String? = null
    @Volatile var serverTz: TimeZone = TimeZone.getTimeZone("UTC")
    var onHostChanged: ((String) -> Unit)? = null

    /** Whether a VPN is on for this app's network; null when unknown. Set by RelayApp from VpnWatch. */
    @Volatile var vpnOn: () -> Boolean? = { null }

    /** Which servers answered and which failed since this start (Settings → Servers, the log). */
    val health = HostHealth { System.currentTimeMillis() }

    /** For every server but the last one tried: a dead server should not hold up the next for 15 s. */
    private val quickConnect: OkHttpClient by lazy { http.newBuilder().connectTimeout(5, TimeUnit.SECONDS).build() }

    /** The servers in the order to try them; see [HostOrder]. */
    fun hostsOrdered(): List<String> {
        val acc = account ?: return emptyList()
        return HostOrder.order(acc.hosts, acc::netOf, vpnOn(), host, health.coolingUntil(), health.clock())
    }

    private fun apiUrl(h: String, acc: Account, params: Map<String, String>): HttpUrl {
        val b = "${h.trimEnd('/')}/player_api.php".toHttpUrl().newBuilder()
            .addQueryParameter("username", acc.username)
            .addQueryParameter("password", acc.password)
        params.forEach { (k, v) -> b.addQueryParameter(k, v) }
        return b.build()
    }

    private class LoginRejectedException(msg: String) : IOException(msg)

    private suspend fun <T> withHosts(params: Map<String, String>, block: (Response) -> T): T =
        withContext(Dispatchers.IO) {
            val acc = account ?: throw IOException("Not signed in")
            val hosts = hostsOrdered()
            if (hosts.isEmpty()) throw IOException("No server configured")
            var last: Exception? = null
            var stage: Int = HostHealth.CONNECTING
            var httpCode: Int = 0
            for ((i, h) in hosts.withIndex()) {
                try {
                    val req = Request.Builder()
                        .url(apiUrl(h, acc, params))
                        .header("User-Agent", userAgent())
                        .build()
                    val client = if (i < hosts.lastIndex) quickConnect else http
                    val t0 = System.nanoTime()
                    stage = HostHealth.CONNECTING
                    httpCode = 0
                    val result = client.newCall(req).readResponse { r ->
                        stage = HostHealth.STATUS
                        httpCode = r.code
                        if (!r.isSuccessful) throw IOException("HTTP ${r.code} from ${r.request.url.host}")
                        stage = HostHealth.BODY
                        val parsed = block(r)
                        health.answered(h, (System.nanoTime() - t0) / 1_000_000)
                        parsed
                    }
                    if (host != h) {
                        host = h
                        onHostChanged?.invoke(h)
                    }
                    return@withContext result
                } catch (e: CancellationException) {
                    throw e
                } catch (e: LoginRejectedException) {
                    throw IOException(e.message)
                } catch (e: Exception) {
                    currentCoroutineContext().ensureActive()
                    AppLog.w("Api", "${params["action"] ?: "login"} failed on $h", e)
                    // Only what says something about the whole server puts it at the back of the queue.
                    if (HostHealth.isServerFailure(params["action"], stage, httpCode)) health.failed(h, e)
                    last = e
                }
            }
            throw last ?: IOException("No server answered")
        }

    /** Register cancellation before entering OkHttp and keep it active until the body is closed. */
    private suspend fun <T> Call.readResponse(read: (Response) -> T): T =
        suspendCancellableCoroutine { pending ->
            pending.invokeOnCancellation { cancel() }
            if (pending.isActive) pending.resumeWith(runCatching { execute().use(read) })
        }

    suspend fun getJson(params: Map<String, String>): JsonElement =
        withHosts(params) { r -> Jsons.json.parseToJsonElement(r.body!!.string()) }

    /** Size, source and Content-Length of a downloaded list, for the log. */
    class Downloaded(val bytes: Long, val host: String, val contentLength: Long?)

    /**
     * Streams a (possibly huge) list response straight to disk. [progress] gets the bytes so far
     * and the server's Content-Length when it sends one (many Xtream servers don't).
     */
    suspend fun download(action: String, dest: File, progress: (Long, Long?) -> Unit = { _, _ -> }): Downloaded {
        val caller = currentCoroutineContext()
        return withHosts(mapOf("action" to action)) { r ->
            val tmp = File.createTempFile("xtream-", ".tmp", dest.absoluteFile.parentFile)
            try {
                val body = r.body ?: throw IOException("Empty $action response")
                val length = body.contentLength().takeIf { it > 0 }
                var total = 0L
                progress(0, length)
                body.byteStream().use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            caller.ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            total += n
                            progress(total, length)
                        }
                    }
                }
                caller.ensureActive()
                if (!startsWithJsonArray(tmp)) throw InvalidListException(action)
                // Rename over the previous file only after the whole download has succeeded.
                if (!tmp.renameTo(dest)) throw IOException("Couldn't save $action")
                Downloaded(total, r.request.url.host, length)
            } finally {
                tmp.delete()
            }
        }
    }

    suspend fun authenticate(): AccountInfo = withHosts(emptyMap()) { r ->
        val root = Jsons.json.parseToJsonElement(r.body!!.string())
        val ui = root.at("user_info") ?: throw IOException("Server didn't return account info. Check the URL.")
        val auth = ui.at("auth")
        if (auth != null && auth.int() == 0) throw LoginRejectedException("Login rejected. Check username and password.")
        root.at("server_info").at("timezone").str()?.let { serverTz = TimeZone.getTimeZone(it) }
        AccountInfo(
            status = ui.at("status").str(),
            expires = ui.at("exp_date").long(),
            maxConnections = ui.at("max_connections").int(),
            activeConnections = ui.at("active_cons").int(),
        )
    }

    suspend fun vodInfo(id: Int): MovieInfo {
        val r = getJson(mapOf("action" to "get_vod_info", "vod_id" to id.toString()))
        val i = r.at("info")
        val md = r.at("movie_data")
        return MovieInfo(
            plot = i.at("plot").str() ?: i.at("description").str(),
            genre = i.at("genre").str(),
            cast = i.at("cast").str() ?: i.at("actors").str(),
            director = i.at("director").str(),
            durationSecs = i.at("duration_secs").int(),
            backdrop = firstImage(i.at("backdrop_path")),
            year = i.at("releasedate").str()?.take(4) ?: i.at("year").str(),
            rating = i.at("rating").dbl(),
            tmdbId = i.at("tmdb_id").str(),
            ext = md.at("container_extension").str(),
            trailer = i.at("youtube_trailer").str(),
        )
    }

    suspend fun seriesInfo(id: Int): SeriesInfo {
        val r = getJson(mapOf("action" to "get_series_info", "series_id" to id.toString()))
        val i = r.at("info")
        val eps = mutableListOf<Episode>()

        fun addEp(seasonKey: Int?, e: JsonElement) {
            val o = e as? JsonObject ?: return
            val epInfo = o["info"]
            val epId = o["id"].str() ?: return
            eps += Episode(
                id = epId,
                season = o["season"].int() ?: seasonKey ?: 1,
                number = o["episode_num"].int() ?: 0,
                title = o["title"].str() ?: "Episode",
                ext = o["container_extension"].str() ?: "mp4",
                plot = epInfo.at("plot").str(),
                image = epInfo.at("movie_image").str(),
                durationSecs = epInfo.at("duration_secs").int(),
            )
        }

        when (val e = r.at("episodes")) {
            is JsonObject -> e.entries.forEach { entry ->
                (entry.value as? JsonArray)?.forEach { addEp(entry.key.toIntOrNull(), it) }
            }
            is JsonArray -> e.forEach { item ->
                if (item is JsonArray) item.forEach { addEp(null, it) } else addEp(null, item)
            }
            else -> Unit
        }
        val seasons = eps
            .distinctBy { it.id }
            .sortedWith(compareBy<Episode>({ it.season }, { it.number }))
            .groupBy { it.season }
        return SeriesInfo(
            plot = i.at("plot").str(),
            genre = i.at("genre").str(),
            cast = i.at("cast").str(),
            backdrop = firstImage(i.at("backdrop_path")),
            rating = i.at("rating").dbl(),
            year = i.at("releaseDate").str()?.take(4) ?: i.at("release_date").str()?.take(4),
            tmdbId = i.at("tmdb").str(),
            seasons = seasons,
            trailer = i.at("youtube_trailer").str(),
        )
    }

    /** Short guide (now/next) or the full archive table for catch-up. */
    suspend fun epg(streamId: Int, full: Boolean, limit: Int = 10): List<EpgEntry> {
        val params = if (full) {
            mapOf("action" to "get_simple_data_table", "stream_id" to "$streamId")
        } else {
            mapOf("action" to "get_short_epg", "stream_id" to "$streamId", "limit" to "$limit")
        }
        val list = getJson(params).at("epg_listings").arr() ?: return emptyList()
        return list.mapNotNull { e ->
            val start = e.at("start_timestamp").long() ?: return@mapNotNull null
            val end = e.at("stop_timestamp").long() ?: e.at("end_timestamp").long() ?: return@mapNotNull null
            EpgEntry(
                title = b64(e.at("title").str()),
                description = b64(e.at("description").str()),
                start = start,
                end = end,
                hasArchive = e.at("has_archive").flag(),
            )
        }.distinctBy { it.start }.sortedBy { it.start }
    }

    fun streamUrls(kind: String, id: String, ext: String): List<String> {
        val acc = account ?: return emptyList()
        val u = Uri.encode(acc.username)
        val p = Uri.encode(acc.password)
        val path = when (kind) {
            "live" -> "live"
            "movie" -> "movie"
            else -> "series"
        }
        return hostsOrdered().map { h -> "${h.trimEnd('/')}/$path/$u/$p/$id.$ext" }
    }

    /** The provider's full XMLTV guide (large); null when signed out. */
    fun xmltvUrl(): String? {
        val acc = account ?: return null
        val h = hostsOrdered().firstOrNull() ?: return null
        return "${h.trimEnd('/')}/xmltv.php?username=${Uri.encode(acc.username)}&password=${Uri.encode(acc.password)}"
    }

    fun catchupUrls(streamId: Int, startSec: Long, endSec: Long): List<String> {
        val acc = account ?: return emptyList()
        val minutes = ((endSec - startSec) / 60).coerceAtLeast(1)
        val fmt = SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.US).apply { timeZone = serverTz }
        val t = fmt.format(Date(startSec * 1000))
        val u = Uri.encode(acc.username)
        val p = Uri.encode(acc.password)
        return hostsOrdered().map { h -> "${h.trimEnd('/')}/timeshift/$u/$p/$minutes/$t/$streamId.ts" }
    }

    private fun firstImage(e: JsonElement?): String? = when (e) {
        is JsonArray -> e.firstOrNull().str()
        else -> e.str()
    }

    private fun b64(s: String?): String {
        if (s.isNullOrEmpty()) return ""
        return try {
            String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8)
        } catch (_: Exception) {
            s
        }
    }

    companion object {
        private fun startsWithJsonArray(file: File): Boolean {
            file.inputStream().use { input ->
                var b = input.read()
                // Skip UTF-8 BOM (EF BB BF) and leading whitespace.
                if (b == 0xEF) {
                    if (input.read() != 0xBB || input.read() != 0xBF) return false
                    b = input.read()
                }
                while (b == ' '.code || b == '\n'.code || b == '\r'.code || b == '\t'.code) {
                    b = input.read()
                }
                return b == '['.code
            }
        }

        private fun isLoopbackOrUnspecified(host: String): Boolean {
            val h = host.lowercase()
            return h == "localhost" || h == "0.0.0.0" || h == "::1" || h == "::" || h.startsWith("127.")
        }

        /** Accepts "host:port", "http://host", or a pasted player_api/get.php URL; rejects fragments and loopback hosts. */
        fun normalizeHost(raw: String, allowLoopback: Boolean = false): String {
            var h = raw.trim()
            if (h.isEmpty() || h == "http://" || h == "https://") return ""
            if (!h.startsWith("http://", true) && !h.startsWith("https://", true)) h = "http://$h"
            h = h.substringBefore("#").substringBefore("?")
                .replace(Regex("(?i)/(player_api|get)\\.php.*$"), "")
                .trimEnd('/')
            val parsed = h.toHttpUrlOrNull() ?: return ""
            if (!allowLoopback && isLoopbackOrUnspecified(parsed.host)) return ""
            val defaultPort = if (parsed.isHttps) 443 else 80
            val portSuffix = if (parsed.port != defaultPort) ":${parsed.port}" else ""
            val hostPart = if (':' in parsed.host) "[${parsed.host}]" else parsed.host
            val basePath = parsed.encodedPath.trimEnd('/').takeIf { it.isNotEmpty() && it != "/" }.orEmpty()
            return "${parsed.scheme}://$hostPart$portSuffix$basePath"
        }
    }
}
