package dev.relay.tv.player

import android.content.Context
import android.content.Intent
import dev.relay.tv.RelayApp
import dev.relay.tv.data.Channel
import dev.relay.tv.data.EpgEntry
import dev.relay.tv.data.Episode
import dev.relay.tv.data.Movie
import dev.relay.tv.data.PlayItem
import dev.relay.tv.data.PlayRequest
import dev.relay.tv.data.Series
import dev.relay.tv.data.WatchEntry

/** Builds play requests and hands them to [PlayerActivity]. */
object Playback {
    private val app get() = RelayApp.instance
    private val pid get() = app.session.profileId

    private fun launch(ctx: Context, req: PlayRequest) {
        app.pendingPlay = req
        ctx.startActivity(Intent(ctx, PlayerActivity::class.java))
    }

    fun live(ctx: Context, channels: List<Channel>, index: Int) {
        if (channels.isEmpty()) return
        val ext = app.store.settings.liveExt
        val items = channels.map {
            PlayItem(
                key = "live:${it.id}", kind = "live", title = it.name,
                subtitle = it.num.takeIf { n -> n > 0 }?.toString(), image = it.logo,
                streamId = it.id.toString(), ext = ext,
            )
        }
        launch(ctx, PlayRequest(pid, items, index.coerceIn(0, items.lastIndex), live = true))
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

    fun catchup(ctx: Context, ch: Channel, e: EpgEntry) {
        val item = PlayItem(
            key = "catchup:${ch.id}:${e.start}", kind = "catchup", title = ch.name, subtitle = e.title,
            image = ch.logo, streamId = ch.id.toString(), ext = "ts",
            urls = app.api.catchupUrls(ch.id, e.start, e.end),
        )
        launch(ctx, PlayRequest(pid, listOf(item), 0))
    }

    /** Resume a single history entry (used when the series episode list isn't loaded). */
    fun resume(ctx: Context, e: WatchEntry) {
        val item = PlayItem(
            key = e.key, kind = e.kind, title = e.title, subtitle = e.subtitle, image = e.image,
            streamId = e.streamId, ext = e.ext, seriesId = e.seriesId,
            resumeMs = if (e.inProgress) e.position else 0,
        )
        launch(ctx, PlayRequest(pid, listOf(item), 0, live = e.kind == "live"))
    }
}
