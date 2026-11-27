package dev.relay.tv.sync

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import dev.relay.tv.RelayApp
import dev.relay.tv.companion.LanHttp
import dev.relay.tv.data.AppLog
import dev.relay.tv.data.Channel
import dev.relay.tv.data.KidsLimits
import dev.relay.tv.data.Movie
import dev.relay.tv.player.Playback
import dev.relay.tv.player.PlayerActivity
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedInputStream
import java.io.IOException
import java.lang.ref.WeakReference
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * "Play on TV", the TV's side. While Relay is open on a TV that syncs, a small receiver listens on the home
 * network and announces itself (`_relay-play._tcp`) with a tag worked out from the household key. A phone of
 * the same household sends a sealed [PlayCommand]; the TV checks it, looks the item up in its own library and
 * plays it under the profile open on the TV. Nothing runs while Relay is in the background.
 */
class PlayReceiver(private val app: RelayApp) : Application.ActivityLifecycleCallbacks {
    private val main = Handler(Looper.getMainLooper())
    private val replay = ReplayGuard()
    private var foreground = 0
    private var front: WeakReference<Activity>? = null
    private var socket: ServerSocket? = null
    private var advertisedTag: String? = null
    private var stopAnnouncing: (() -> Unit)? = null
    private var pool = Executors.newFixedThreadPool(2) { r -> Thread(r, "relay-play").apply { isDaemon = true } }

    /** Starts or stops the receiver to match whether it should run now. Main thread. */
    fun refresh() {
        val key = app.sync.settings.config?.key
        val tag = key?.let(PlayKeys::tag)
        val want = foreground > 0 && key != null && tag != null && app.isTv() && app.store.account != null
        if (want && socket != null && tag != advertisedTag) stop()
        if (want && socket == null) start(tag!!) else if (!want && socket != null) stop()
    }

    private fun start(tag: String) {
        val server = try {
            ServerSocket(0)
        } catch (e: Exception) {
            AppLog.w("PlayOnTv", "Could not open the Play on TV receiver", e)
            return
        }
        socket = server
        advertisedTag = tag
        if (pool.isShutdown) pool = Executors.newFixedThreadPool(2) { r -> Thread(r, "relay-play").apply { isDaemon = true } }
        val workers = pool
        Thread({
            while (!server.isClosed) {
                val client = try {
                    server.accept()
                } catch (_: Exception) {
                    break
                }
                try {
                    workers.execute { serve(client) }
                } catch (_: Exception) {
                    try { client.close() } catch (_: Exception) {}
                }
            }
        }, "relay-play-accept").apply { isDaemon = true }.start()
        stopAnnouncing = PairDiscovery.advertiseAs(
            app, TYPE, app.sync.screenName(), server.localPort,
            mapOf(ATTR_TAG to tag, ATTR_VERSION to PlayCommand.VERSION.toString()),
            "PlayOnTv", "Ready for Play on TV from a phone",
        )
    }

    private fun stop() {
        stopAnnouncing?.invoke()
        stopAnnouncing = null
        advertisedTag = null
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        pool.shutdownNow()
    }

    private fun serve(s: Socket) {
        try {
            s.soTimeout = 10_000
            s.use { sock ->
                val req = LanHttp.read(sock, BufferedInputStream(sock.getInputStream()), PlayCommand.MAX_BYTES) ?: return
                if (req.method != "POST" || req.path != "/play") {
                    LanHttp.respond(sock, 404, "text/plain", "Not found".toByteArray())
                    return
                }
                val (code, text) = handle(req.body)
                LanHttp.respond(sock, code, "text/plain; charset=utf-8", text.toByteArray(Charsets.UTF_8))
            }
        } catch (e: Exception) {
            AppLog.w("PlayOnTv", "Request failed", e)
        }
    }

    /** The status code and the words the phone shows. */
    private fun handle(body: ByteArray): Pair<Int, String> {
        val key = app.sync.settings.config?.key ?: return 403 to "This TV no longer uses sync."
        val now = System.currentTimeMillis()
        val c = try {
            PlayCodec.open(body, key, now)
        } catch (e: PlayRejected) {
            AppLog.w("PlayOnTv", "Refused a request: ${e.message}")
            return 403 to (e.message ?: "Refused.")
        }
        if (!replay.firstTime(c.nonce, now)) {
            AppLog.w("PlayOnTv", "Refused a repeated request")
            return 403 to "That request was already used."
        }
        return onMain { play(c) }
    }

    /** Main thread: decide, then start playing. */
    private fun play(c: PlayCommand): Pair<Int, String> {
        val name = app.sync.screenName()
        front?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
            ?: return 409 to "Relay isn't open on $name."
        val decision = PlayDecider.decide(c, lookups(), name)
        AppLog.i("PlayOnTv", "Request from ${c.from.take(4)}: ${c.kind} '${c.title}' → ${decision::class.simpleName}")
        if (decision is PlayDecision.Refuse) return 409 to decision.reason
        // A film already playing here keeps its place before the new item takes over; use Application context
        // so starting the next item never touches the finishing PlayerActivity.
        PlayerActivity.current?.get()?.finishForHandover()
        when (decision) {
            is PlayDecision.Live -> Playback.live(app, decision.channels, decision.index)
            is PlayDecision.Film -> Playback.movie(app, decision.movie, decision.movie.ext, decision.positionMs)
            is PlayDecision.Episode -> app.appScope.launch { Playback.resumeEpisode(app, decision.entry, decision.positionMs) }
            is PlayDecision.Catchup -> Playback.catchup(app, decision.channel, decision.programme, decision.positionMs)
            is PlayDecision.Refuse -> Unit
        }
        return 200 to "Playing on $name"
    }

    private fun lookups() = object : PlayLookups {
        private val catalog = app.catalog
        private val restrictions = catalog.restrictions(app.session.restrict(app.store.settings))
        override val profileOpen get() = app.session.profile != null
        override val libraryReady get() = !catalog.isEmpty
        override val viewingBlocked: String?
            get() = (app.store.viewingVerdict(app.session.profileId) as? KidsLimits.Verdict.Blocked)?.reason
        override fun channel(id: Int): Channel? = catalog.channelById[id]
        override fun channelsIn(categoryId: String): List<Channel> = catalog.channelsByCat[categoryId].orEmpty()
        override fun movie(id: Int): Movie? = catalog.movieById[id]
        override fun seriesCategory(id: Int): String? = catalog.seriesById[id]?.categoryId
        override fun allows(categoryId: String): Boolean = restrictions.allowsCategory(categoryId)
    }

    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val task = FutureTask(Callable(block))
        main.post(task)
        return task.get(10, TimeUnit.SECONDS)
    }

    override fun onActivityStarted(activity: Activity) {
        foreground++
        refresh()
    }

    override fun onActivityStopped(activity: Activity) {
        foreground = (foreground - 1).coerceAtLeast(0)
        refresh()
    }

    override fun onActivityResumed(activity: Activity) {
        front = WeakReference(activity)
        // Sync may have been turned on or off while Relay was open.
        refresh()
    }

    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) {
        if (front?.get() === activity) front = null
    }

    companion object {
        const val TYPE = "_relay-play._tcp."
        const val ATTR_TAG = "h"
        const val ATTR_VERSION = "v"
    }
}

/** "Play on TV", the phone's side: finding this household's TVs and sending them a command. */
object PlayOnTvClient {
    /** Calls [onChange] on the main thread with the TVs of the household whose key is [householdKey]. Returns the function that stops looking. */
    fun discover(ctx: Context, householdKey: ByteArray, onChange: (List<FoundScreen>) -> Unit): () -> Unit {
        val tag = PlayKeys.tag(householdKey)
        return PairDiscovery.discoverAs(ctx, PlayReceiver.TYPE, "", "PlayOnTv") { list ->
            onChange(list.filter { it.attributes[PlayReceiver.ATTR_TAG] == tag })
        }
    }

    class Answer(val ok: Boolean, val message: String)

    /** Sends [command] to [tv]. Blocking: run off the main thread. */
    fun send(http: OkHttpClient, tv: FoundScreen, command: PlayCommand, householdKey: ByteArray): Answer {
        val sealed = PlayCodec.seal(command, householdKey)
        val client = http.newBuilder().callTimeout(10, TimeUnit.SECONDS).build()
        val request = Request.Builder()
            .url("http://${tv.host}:${tv.port}/play")
            .post(sealed.toRequestBody("application/octet-stream".toMediaType()))
            .build()
        return try {
            client.newCall(request).execute().use { r ->
                val text = r.body?.string().orEmpty().take(300)
                Answer(r.code == 200, text.ifBlank { "${tv.name} answered in a way Relay doesn't understand (${r.code})." })
            }
        } catch (e: IOException) {
            Answer(false, "Couldn't reach ${tv.name}. Both need to be on the same Wi-Fi, with Relay open on the TV.")
        }
    }

    /** What the phone sends for a [dev.relay.tv.data.PlayItem] that is playing, or null for something a TV can't take. */
    fun commandFor(item: dev.relay.tv.data.PlayItem, positionMs: Long, deviceId: String, now: Long = System.currentTimeMillis()): PlayCommand? {
        val kind = when (item.kind) {
            "live" -> PlayCommand.LIVE
            "movie" -> PlayCommand.MOVIE
            "episode" -> PlayCommand.EPISODE
            "catchup" -> PlayCommand.CATCHUP
            else -> return null
        }
        if (kind == PlayCommand.EPISODE && item.seriesId == null) return null
        if (kind == PlayCommand.CATCHUP) {
            if (item.catchupStart <= 0 || item.catchupEnd <= item.catchupStart) return null
            // The programme's name is in the subtitle; the title is the channel.
            return catchupCommand(item.streamId, item.catchupStart, item.catchupEnd, item.subtitle ?: item.title, positionMs, deviceId, now)
        }
        return PlayCommand(
            from = deviceId, nonce = PlayCommand.newNonce(), sentAt = now, kind = kind, id = item.streamId,
            seriesId = item.seriesId, positionMs = if (kind == PlayCommand.LIVE) 0 else positionMs.coerceAtLeast(0),
            ext = if (kind == PlayCommand.EPISODE) item.ext.filter { it.isLetterOrDigit() }.take(10) else "",
            title = item.title.take(300),
        )
    }

    /** An episode from a series page, at [positionMs]. */
    fun episodeCommand(
        episodeId: String, seriesId: Int, ext: String, title: String, positionMs: Long, deviceId: String,
        now: Long = System.currentTimeMillis(),
    ) = PlayCommand(
        from = deviceId, nonce = PlayCommand.newNonce(), sentAt = now, kind = PlayCommand.EPISODE, id = episodeId,
        seriesId = seriesId, positionMs = positionMs.coerceAtLeast(0), ext = ext.filter { it.isLetterOrDigit() }.take(10),
        title = title.take(300),
    )

    /** A recorded programme of channel [channelId], by the provider's own times, from [positionMs]. */
    fun catchupCommand(
        channelId: String, start: Long, end: Long, title: String, positionMs: Long, deviceId: String,
        now: Long = System.currentTimeMillis(),
    ) = PlayCommand(
        v = PlayCommand.VERSION_CATCHUP, from = deviceId, nonce = PlayCommand.newNonce(), sentAt = now,
        kind = PlayCommand.CATCHUP, id = channelId, positionMs = positionMs.coerceAtLeast(0), title = title.take(300),
        start = start, end = end,
    )
}
