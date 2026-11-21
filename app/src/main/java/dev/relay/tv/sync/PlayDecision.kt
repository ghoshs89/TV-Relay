package dev.relay.tv.sync

import dev.relay.tv.data.Channel
import dev.relay.tv.data.Movie
import dev.relay.tv.data.WatchEntry

/** What a TV does with a [PlayCommand] it accepted. Pure, so the rules can be tested without a TV. */
sealed interface PlayDecision {
    /** Tune [channels] at [index]; Up and Down then move through that list. */
    data class Live(val channels: List<Channel>, val index: Int) : PlayDecision
    data class Film(val movie: Movie, val positionMs: Long) : PlayDecision
    /** Start an episode at [positionMs] the way Continue watching does (the next episodes are queued after it). */
    data class Episode(val entry: WatchEntry, val positionMs: Long) : PlayDecision
    /** Play a recorded programme of [channel] from [positionMs]. [programme] carries the provider's times. */
    data class Catchup(val channel: Channel, val programme: dev.relay.tv.data.EpgEntry, val positionMs: Long) : PlayDecision
    data class Refuse(val reason: String) : PlayDecision
}

/** What the decision reads from the TV. Implemented over the app's catalog and session; fakes in tests. */
interface PlayLookups {
    /** A profile is open on the TV (not the profile picker or sign-in screen). */
    val profileOpen: Boolean

    /** The TV has its library loaded. */
    val libraryReady: Boolean

    /** Why this profile may not watch anything right now (a kids' time limit or bedtime), or null. */
    val viewingBlocked: String?
    fun channel(id: Int): Channel?
    fun channelsIn(categoryId: String): List<Channel>
    fun movie(id: Int): Movie?

    /** The category of a series, or null if the TV's library doesn't have it. */
    fun seriesCategory(id: Int): String?

    /** The open profile may see this category (kids profiles and hidden adult categories say no). */
    fun allows(categoryId: String): Boolean
}

object PlayDecider {
    /** [nowSec] is the TV's clock, in seconds, for the catch-up window. */
    fun decide(c: PlayCommand, tv: PlayLookups, tvName: String, nowSec: Long = System.currentTimeMillis() / 1000): PlayDecision {
        if (!tv.profileOpen) return PlayDecision.Refuse("Pick a profile on $tvName first.")
        if (!tv.libraryReady) return PlayDecision.Refuse("$tvName is still loading its channels. Try again in a moment.")
        tv.viewingBlocked?.let { return PlayDecision.Refuse(it) }
        val notHere = PlayDecision.Refuse("${c.title.ifBlank { "That" }} isn't in the library on $tvName.")
        val notAllowed = PlayDecision.Refuse("Not available on the profile open on $tvName.")
        return when (c.kind) {
            PlayCommand.LIVE -> {
                val ch = c.id.toIntOrNull()?.let { tv.channel(it) } ?: return notHere
                if (!tv.allows(ch.categoryId)) return notAllowed
                // Up and Down zap through the channel's own category, as when a channel is started on the TV.
                val list = tv.channelsIn(ch.categoryId).filter { tv.allows(it.categoryId) }.ifEmpty { listOf(ch) }
                PlayDecision.Live(list, list.indexOfFirst { it.id == ch.id }.coerceAtLeast(0))
            }
            PlayCommand.MOVIE -> {
                val m = c.id.toIntOrNull()?.let { tv.movie(it) } ?: return notHere
                if (!tv.allows(m.categoryId)) return notAllowed
                PlayDecision.Film(m, c.positionMs)
            }
            PlayCommand.EPISODE -> {
                val seriesId = c.seriesId ?: return notHere
                val cat = tv.seriesCategory(seriesId) ?: return notHere
                if (!tv.allows(cat)) return notAllowed
                PlayDecision.Episode(
                    WatchEntry(
                        key = "ep:${c.id}", kind = "episode", title = c.title, streamId = c.id,
                        ext = c.ext.ifBlank { "mp4" }, seriesId = seriesId, position = c.positionMs,
                    ),
                    c.positionMs,
                )
            }
            PlayCommand.CATCHUP -> {
                val ch = c.id.toIntOrNull()?.let { tv.channel(it) } ?: return notHere
                if (!tv.allows(ch.categoryId)) return notAllowed
                if (!ch.hasArchive) return PlayDecision.Refuse("${ch.name} has no catch-up on $tvName.")
                // A little slack: the phone listed it as available a moment ago.
                if (c.start < nowSec - ch.archiveDays * 86_400L - 3_600) return PlayDecision.Refuse("That programme is no longer available on catch-up.")
                PlayDecision.Catchup(
                    ch,
                    dev.relay.tv.data.EpgEntry(c.title, "", c.start, c.end, true, rawStart = c.start, rawEnd = c.end),
                    c.positionMs,
                )
            }
            else -> PlayDecision.Refuse("Update Relay on both screens to use Play on TV.")
        }
    }
}
