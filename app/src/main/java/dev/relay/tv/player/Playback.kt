package dev.relay.tv.player

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.widget.Toast
import dev.relay.tv.RelayApp
import dev.relay.tv.data.AppLog
import dev.relay.tv.data.Channel
import dev.relay.tv.data.EpgEntry
import dev.relay.tv.data.Episode
import dev.relay.tv.data.KidsLimits
import dev.relay.tv.data.LazyItems
import dev.relay.tv.data.Movie
import dev.relay.tv.data.PlayItem
import dev.relay.tv.data.PlayRequest
import dev.relay.tv.data.Series
import dev.relay.tv.data.WatchEntry
import kotlinx.coroutines.launch

/** Builds play requests and hands them to [PlayerActivity]. */
object Playback {
    private val app get() = RelayApp.instance
    private val pid get() = app.session.profileId

    /** Every play request passes the profile's restrictions here; blocked items never reach the player. */
    private fun launch(ctx: Context, req: PlayRequest) {
        val r = app.catalog.restrictions(app.session.restrict(app.store.settings))
        val limit = app.store.viewingVerdict(req.profileId)
        if (limit is KidsLimits.Verdict.Blocked) {
            AppLog.i("Access", "Viewing limit: ${limit.reason}")
            Toast.makeText(ctx, limit.reason + " A parent can add time in Settings.", Toast.LENGTH_LONG).show()
            return
        }
        val ok = r.admit(req)
        if (ok == null) {
            AppLog.i("Access", "Blocked on this profile: ${req.items.getOrNull(req.index)?.kind}")
            Toast.makeText(ctx, "Not available on this profile", Toast.LENGTH_SHORT).show()
            return
        }
        app.pendingPlay = ok
        ctx.startActivity(Intent(ctx, PlayerActivity::class.java))
    }

    /** Entries for live channels, created only as the player reaches them (see [LazyItems]). */
    fun liveItems(channels: List<Channel>): LazyItems {
        val ext = app.store.settings.liveExt
        return LazyItems(channels) {
            PlayItem(
                key = "live:${it.id}", kind = "live", title = it.name,
                subtitle = it.num.takeIf { n -> n > 0 }?.toString(), image = it.logo,
                streamId = it.id.toString(), ext = ext, alternatesKnown = false,
            )
        }
    }

    fun live(ctx: Context, channels: List<Channel>, index: Int) {
        if (channels.isEmpty()) return
        val t = SystemClock.elapsedRealtime()
        val chosen = channels[index.coerceIn(0, channels.lastIndex)]
        val r = app.catalog.restrictions(app.session.restrict(app.store.settings))
        // Restricted profiles lose blocked channels here, from the channels, so no play entries are built for them.
        val visible = if (r.restrict) channels.filter { r.allowsCategory(it.categoryId) } else channels
        val at = if (visible === channels) index.coerceIn(0, channels.lastIndex) else visible.indexOfFirst { it.id == chosen.id }
        if (at < 0) {
            // The chosen channel is blocked: let the usual check refuse it and say so.
            launch(ctx, PlayRequest(pid, liveItems(listOf(chosen)), 0, live = true))
            return
        }
        val fallbacks: ((Int) -> List<String>)? =
            if (app.store.settings.mergeVariants) { id -> r.fallbackIds(id, app.catalog.versionsOf(id)) } else null
        launch(ctx, PlayRequest(pid, liveItems(visible), at, live = true, vetted = true, fallbacks = fallbacks))
        AppLog.d("Player", "Play request for ${visible.size} channels built in ${SystemClock.elapsedRealtime() - t} ms")
    }

    fun movie(ctx: Context, m: Movie, ext: String, resumeMs: Long) {
        launch(
            ctx,
            PlayRequest(
                pid,
                listOf(
                    PlayItem(
                        key = "movie:${m.id}", kind = "movie", title = m.name, subtitle = m.year,
                        image = m.poster, streamId = m.id.toString(), ext = ext, resumeMs = resumeMs,
                    ),
                ),
                0,
            ),
        )
    }

    /** Queues every episode in order so "next episode" works across seasons. */
    fun episodes(ctx: Context, s: Series, all: List<Episode>, index: Int, resumeMs: Long) {
        if (all.isEmpty()) return
        val items = all.mapIndexed { i, e ->
            PlayItem(
                key = "ep:${e.id}", kind = "episode", title = s.name,
                subtitle = "S${e.season} E${e.number} · ${e.title}", image = e.image ?: s.cover,
                streamId = e.id, ext = e.ext, seriesId = s.id, resumeMs = if (i == index) resumeMs else 0,
            )
        }
        launch(ctx, PlayRequest(pid, items, index.coerceIn(0, items.lastIndex)))
    }

    fun catchup(ctx: Context, ch: Channel, e: EpgEntry, resumeMs: Long = 0) {
        val item = PlayItem(
            key = "catchup:${ch.id}:${e.rawStart}", kind = "catchup", title = ch.name, subtitle = e.title,
            image = ch.logo, streamId = ch.id.toString(), ext = "ts",
            // Provider times, not the offset-adjusted ones shown in the guide.
            urls = app.api.catchupUrls(ch.id, e.rawStart, e.rawEnd),
            resumeMs = resumeMs, catchupStart = e.rawStart, catchupEnd = e.rawEnd,
        )
        launch(ctx, PlayRequest(pid, listOf(item), 0))
    }

    /** Resume a single history entry (used when the series episode list isn't loaded). */
    fun resume(ctx: Context, e: WatchEntry, resumeMs: Long = if (e.inProgress) e.position else 0) {
        val item = PlayItem(
            key = e.key, kind = e.kind, title = e.title, subtitle = e.subtitle, image = e.image,
            streamId = e.streamId, ext = e.ext, seriesId = e.seriesId,
            resumeMs = resumeMs,
        )
        launch(ctx, PlayRequest(pid, listOf(item), 0, live = e.kind == "live"))
    }

    /**
     * Plays an episode at [positionMs] with the rest of its series queued after it, as Continue watching
     * does. The series has to be fetched for that: a message says so if it takes a moment, and only this
     * episode plays if it can't be fetched within 8 seconds.
     */
    suspend fun resumeEpisode(ctx: Context, e: WatchEntry, positionMs: Long) = kotlinx.coroutines.coroutineScope {
        val sid = e.seriesId ?: return@coroutineScope resume(ctx, e, positionMs)
        val series = app.catalog.seriesById[sid]
        val hint = launch {
            kotlinx.coroutines.delay(500)
            Toast.makeText(ctx, "Opening ${e.title}…", Toast.LENGTH_SHORT).show()
        }
        val info = kotlinx.coroutines.withTimeoutOrNull(8_000) { runCatching { app.api.seriesInfo(sid) }.getOrNull() }
        hint.cancel()
        if (info == null) {
            Toast.makeText(ctx, "Couldn't load the whole series; playing just this episode", Toast.LENGTH_LONG).show()
        }
        val all: List<Episode> = info?.let { inf -> inf.seasons.keys.sorted().flatMap { inf.seasons[it].orEmpty() } }.orEmpty()
        val idx = all.indexOfFirst { "ep:${it.id}" == e.key }
        if (series != null && idx >= 0) episodes(ctx, series, all, idx, positionMs) else resume(ctx, e, positionMs)
    }
}
