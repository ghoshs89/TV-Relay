package dev.relay.tv.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPInputStream

/**
 * Optional external guide from an XMLTV URL (plain or .gz), used for channels where the
 * provider has no guide. The file is parsed while it streams in, never saved whole; only
 * programmes for your channels within yesterday..+2 days are kept (a few MB at most).
 */
class XmltvGuide(private val ctx: Context, private val http: OkHttpClient) {

    @Serializable
    internal class Prog(val s: Long, val e: Long, val t: String, val d: String = "")

    @Serializable
    internal class Cache(val url: String, val fetched: Long, val byChannel: Map<Int, List<Prog>>)

    @Volatile private var data: Cache? = null
    @Volatile private var loadAttempted = false
    private val loadLock = Mutex()
    private val file: File get() = File(ctx.filesDir, "xmltv.json")

    /** Shown in Settings. */
    var status by mutableStateOf<String?>(null)

    private fun setStatus(value: String?) {
        Snapshot.withMutableSnapshot { status = value }
    }

    fun entries(channelId: Int): List<EpgEntry> =
        data?.byChannel?.get(channelId)?.map { EpgEntry(it.t, it.d, it.s, it.e, hasArchive = false) }.orEmpty()

    /** Programmes not yet over whose title matches, as (channel id, entry); soonest first. */
    fun search(match: (String) -> Boolean, limit: Int = 80): List<Pair<Int, EpgEntry>> {
        val d = data ?: return emptyList()
        val now = System.currentTimeMillis() / 1000
        val out = ArrayList<Pair<Int, EpgEntry>>()
        for ((id, progs) in d.byChannel) {
            for (p in progs) {
                if (p.e > now && match(p.t)) out += id to EpgEntry(p.t, p.d, p.s, p.e, hasArchive = false)
            }
            if (out.size > limit * 4) break
        }
        return out.sortedBy { it.second.start }.take(limit)
    }

    /** True when this guide was loaded from [key] (a URL, or "provider" for the provider's own). */
    val source: String? get() = data?.url

    /** Loads the saved guide once per session so cold-start channel rows and searches can read it immediately. */
    suspend fun ensureLoaded() {
        if (loadAttempted || data != null) return
        loadLock.withLock {
            if (!loadAttempted && data == null) {
                loadAttempted = true
                load()
            }
        }
    }

    suspend fun needsRefresh(url: String): Boolean {
        ensureLoaded()
        val d = data ?: return true
        return d.url != url || System.currentTimeMillis() - d.fetched > REFRESH_MS
    }

    private suspend fun load() = withContext(Dispatchers.IO) {
        try {
            if (file.exists()) {
                val loaded = Jsons.json.decodeFromString(Cache.serializer(), file.readText())
                data = loaded
                setStatus(summary(loaded))
            }
        } catch (e: Exception) {
            AppLog.w("Epg", "Saved external guide unreadable", e)
        }
    }

    fun clear() {
        data = null
        loadAttempted = false
        val target = file
        if (target.exists()) {
            val dead = File(target.parentFile, "xmltv_${System.nanoTime()}.del")
            if (target.renameTo(dead)) Thread { dead.delete() }.start() else target.delete()
        }
        setStatus(null)
    }

    /** [key] is saved instead of the URL, so a URL with a login in it never reaches the disk. */
    suspend fun refresh(url: String, channels: List<Channel>, key: String = url) {
        setStatus("Downloading…")
        val started = System.currentTimeMillis()
        val cache = withContext(Dispatchers.IO) {
            val req = Request.Builder().url(url).header("User-Agent", "RelayTV/1.0").build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw IOException("Guide download failed (HTTP ${r.code})")
                val body = r.body ?: throw IOException("Empty guide")
                parseStream(bounded(maybeGunzip(body.byteStream()), MAX_STREAM_BYTES), channels, key)
            }
        }
        withContext(Dispatchers.IO) { file.writeText(Jsons.json.encodeToString(Cache.serializer(), cache)) }
        data = cache
        loadAttempted = true
        setStatus(summary(cache))
        AppLog.i(
            "Epg",
            "External guide: ${cache.byChannel.size} channels matched in ${(System.currentTimeMillis() - started) / 1000}s, " +
                "${file.length() / 1024} KB saved",
        )
    }

    private fun summary(c: Cache): String {
        val ago = (System.currentTimeMillis() - c.fetched) / 60_000
        return "${c.byChannel.size} channels matched · updated ${if (ago < 60) "${ago}m" else "${ago / 60}h"} ago"
    }

    companion object {
        private const val REFRESH_MS = 12 * 3600_000L
        internal const val MAX_PER_CHANNEL = 150
        private const val MAX_COLLECT_PER_CHANNEL = MAX_PER_CHANNEL * 4
        internal const val MAX_STREAM_BYTES = 256L * 1_048_576L
        private val PREFIX = Regex("^\\s*[\\[(|]?\\s*[A-Za-z]{2,3}\\s*[\\])|]?\\s*[:|\\-]\\s*")
        private val NON_ALNUM = Regex("[^\\p{L}\\p{N}]+")

        /** "CA: CBC News HD" and "CBC News" compare equal. */
        fun norm(name: String): String = name.replace(PREFIX, "")
            .lowercase()
            .replace(Regex("\\b(hd|fhd|uhd|4k|sd|hevc)\\b"), "")
            .replace(NON_ALNUM, "")

        internal fun maybeGunzip(input: InputStream): InputStream {
            val b = BufferedInputStream(input, 64 * 1024)
            b.mark(2)
            val m1 = b.read()
            val m2 = b.read()
            b.reset()
            return if (m1 == 0x1f && m2 == 0x8b) GZIPInputStream(b, 64 * 1024) else b
        }

        internal fun bounded(input: InputStream, maxBytes: Long): InputStream = object : InputStream() {
            private var seen = 0L
            override fun read(): Int {
                val r = input.read()
                if (r >= 0 && ++seen > maxBytes) throw IOException("Guide exceeds ${maxBytes / 1_048_576} MB limit")
                return r
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = input.read(b, off, len)
                if (n > 0) {
                    seen += n
                    if (seen > maxBytes) throw IOException("Guide exceeds ${maxBytes / 1_048_576} MB limit")
                }
                return n
            }

            override fun close() = input.close()
        }

        internal fun parseStream(
            input: InputStream,
            channels: List<Channel>,
            url: String,
            nowSec: Long = System.currentTimeMillis() / 1000,
        ): Cache {
            val byEpgId = channels.filter { !it.epgId.isNullOrBlank() }.groupBy { it.epgId!!.lowercase() }
            val byName = channels.groupBy { norm(it.name) }
            val from = nowSec - 86_400
            val to = nowSec + 2 * 86_400

            val xmlToIds = HashMap<String, List<Int>>()
            val out = HashMap<Int, MutableList<Prog>>()

            val fmtZone = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
            val fmtUtc = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            fun parseTime(v: String?): Long {
                if (v.isNullOrBlank()) return 0
                val t = v.trim()
                return try {
                    (if (t.length > 14) fmtZone.parse(t) else fmtUtc.parse(t.take(14)))?.time?.div(1000) ?: 0
                } catch (_: Exception) {
                    try {
                        fmtUtc.parse(t.take(14))?.time?.div(1000) ?: 0
                    } catch (_: Exception) {
                        0
                    }
                }
            }

            val p = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }.newPullParser()
            p.setInput(input, null)

            var chId: String? = null
            val names = mutableListOf<String>()
            var progCh: String? = null
            var start = 0L
            var stop = 0L
            var title = ""
            var desc = ""

            var event = p.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (p.name) {
                        "channel" -> {
                            chId = p.getAttributeValue(null, "id")
                            names.clear()
                        }
                        "display-name" -> if (chId != null) names += p.nextText()
                        "programme" -> {
                            progCh = p.getAttributeValue(null, "channel")
                            start = parseTime(p.getAttributeValue(null, "start"))
                            stop = parseTime(p.getAttributeValue(null, "stop"))
                            title = ""
                            desc = ""
                        }
                        "title" -> if (progCh != null && title.isEmpty()) title = p.nextText()
                        "desc" -> if (progCh != null && desc.isEmpty()) desc = p.nextText()
                    }
                } else if (event == XmlPullParser.END_TAG) {
                    when (p.name) {
                        "channel" -> {
                            val id = chId
                            if (id != null) {
                                val hits = byEpgId[id.lowercase()] ?: names.firstNotNullOfOrNull { byName[norm(it)] }
                                if (hits != null) xmlToIds[id] = hits.map { it.id }
                            }
                            chId = null
                        }
                        "programme" -> {
                            val pc = progCh
                            if (pc != null && start > 0 && stop > start && stop > from && start < to && title.isNotBlank()) {
                                val ids = xmlToIds[pc] ?: byEpgId[pc.lowercase()]?.map { it.id }
                                ids?.forEach { cid ->
                                    val list = out.getOrPut(cid) { mutableListOf() }
                                    if (list.size < MAX_COLLECT_PER_CHANNEL) {
                                        list += Prog(start, stop, title.trim(), desc.trim().take(300))
                                    }
                                }
                            }
                            progCh = null
                        }
                    }
                }
                event = p.next()
            }
            return Cache(
                url,
                System.currentTimeMillis(),
                out.mapValues { (_, v) -> v.sortedBy { it.s }.take(MAX_PER_CHANNEL) },
            )
        }
    }
}
