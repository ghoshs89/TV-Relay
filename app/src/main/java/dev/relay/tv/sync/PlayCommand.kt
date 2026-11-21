package dev.relay.tv.sync

import dev.relay.tv.data.Jsons
import dev.relay.tv.data.SealException
import dev.relay.tv.data.SyncSeal
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * "Play on TV": what a phone asks a TV in the same sync household to play. It names the item by its id in
 * the provider's library and never carries a stream address, user name or password; the TV looks the item
 * up in its own library and plays it with its own player.
 */
@Serializable
data class PlayCommand(
    val v: Int = VERSION,
    /** The sending screen's device id (not secret; for the log). */
    val from: String,
    /** Random per command, so the same sealed message can't be played twice. */
    val nonce: String,
    /** When it was sent, by the sender's clock (milliseconds). */
    val sentAt: Long,
    /** live, movie or episode. */
    val kind: String,
    /** Channel id, film id or episode id in the provider's library. */
    val id: String,
    val seriesId: Int? = null,
    /** Where to start a film or episode; 0 from the beginning. */
    val positionMs: Long = 0,
    /** The episode's file type (needed to play an episode without its series list). */
    val ext: String = "",
    /** For the TV's log and messages only. */
    val title: String = "",
    /** Catch-up: the programme's start and end in the provider's own time (seconds). */
    val start: Long = 0,
    val end: Long = 0,
) {
    companion object {
        /** Live, film and episode. TVs on 1.0.115 understand only this. */
        const val VERSION = 1

        /** Catch-up. A TV that doesn't know it answers "Update Relay on both screens". */
        const val VERSION_CATCHUP = 2

        /** The newest this Relay understands. */
        const val MAX_VERSION = VERSION_CATCHUP
        const val LIVE = "live"
        const val MOVIE = "movie"
        const val EPISODE = "episode"
        const val CATCHUP = "catchup"
        val KINDS = setOf(LIVE, MOVIE, EPISODE, CATCHUP)

        /** Longest programme a catch-up command may name. */
        const val MAX_PROGRAMME_SEC = 12 * 3600L

        /** Biggest sealed command a TV accepts. */
        const val MAX_BYTES = 16 * 1024

        private val random = SecureRandom()

        fun newNonce(): String = ByteArray(12).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
    }
}

/** Why a sealed command was not accepted. Shown on the phone, so it is plain. */
class PlayRejected(message: String) : Exception(message)

/**
 * The keys "Play on TV" uses, worked out from the household's sync key so every screen in the household
 * has them and nothing new has to be paired. Neither is the sync key itself: the play key only seals play
 * commands, and the tag only lets a phone tell its own household's TVs from other people's.
 */
object PlayKeys {
    fun playKey(householdKey: ByteArray): ByteArray = sha("relay-play-key|", householdKey)

    /** 8 hex characters announced on the network next to the TV's name. Reveals nothing about the key. */
    fun tag(householdKey: ByteArray): String = sha("relay-play-tag|", householdKey).take(4).joinToString("") { "%02x".format(it) }

    private fun sha(label: String, key: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").run {
            update(label.toByteArray(Charsets.UTF_8))
            digest(key)
        }
}

object PlayCodec {
    /** How far apart the two screens' clocks and the network may put a command, either way. */
    const val MAX_AGE_MS = 5 * 60_000L

    fun seal(c: PlayCommand, householdKey: ByteArray): ByteArray =
        SyncSeal.seal(Jsons.json.encodeToString(c).toByteArray(Charsets.UTF_8), PlayKeys.playKey(householdKey))

    /**
     * The command in [blob], if it was sealed by a screen of this household, is recent and well formed.
     * Throws [PlayRejected] with a plain reason otherwise. [now] is this screen's clock.
     */
    fun open(blob: ByteArray, householdKey: ByteArray, now: Long): PlayCommand {
        if (blob.size > PlayCommand.MAX_BYTES) throw PlayRejected("The request was too large.")
        val plain = try {
            SyncSeal.open(blob, PlayKeys.playKey(householdKey))
        } catch (e: SealException) {
            throw PlayRejected("This TV is not in the same sync household as your phone.")
        } catch (e: Exception) {
            throw PlayRejected("The request was damaged.")
        }
        if (plain.size > PlayCommand.MAX_BYTES) throw PlayRejected("The request was too large.")
        val c = try {
            Jsons.json.decodeFromString(PlayCommand.serializer(), String(plain, Charsets.UTF_8))
        } catch (e: Exception) {
            throw PlayRejected("The request was damaged.")
        }
        if (c.v !in PlayCommand.VERSION..PlayCommand.MAX_VERSION) throw PlayRejected("Update Relay on both screens to use Play on TV.")
        if (c.kind !in PlayCommand.KINDS || c.id.isBlank() || c.id.length > 40 || c.nonce.length !in 16..64) {
            throw PlayRejected("The request was damaged.")
        }
        if (c.positionMs < 0 || c.ext.length > 10 || !c.ext.all { it.isLetterOrDigit() } || c.title.length > 300) {
            throw PlayRejected("The request was damaged.")
        }
        if (c.kind == PlayCommand.EPISODE && c.seriesId == null) throw PlayRejected("The request was damaged.")
        if (c.kind == PlayCommand.CATCHUP) {
            val ok = c.start > 0 && c.end > c.start && c.end - c.start <= PlayCommand.MAX_PROGRAMME_SEC &&
                c.start * 1000 <= now + PlayCodec.MAX_AGE_MS
            if (!ok) throw PlayRejected("The request was damaged.")
        }
        if (kotlin.math.abs(now - c.sentAt) > MAX_AGE_MS) {
            throw PlayRejected("The phone's and the TV's clocks are too far apart, or the request is old. Check the time on both.")
        }
        return c
    }
}

/** Remembers the nonces of recent commands so a recorded one can't be sent again. Thread-safe. */
class ReplayGuard(private val keepMs: Long = 2 * PlayCodec.MAX_AGE_MS, private val max: Int = 500) {
    private val seen = LinkedHashMap<String, Long>()

    /** True the first time [nonce] is offered; false if it was seen within [keepMs]. */
    @Synchronized
    fun firstTime(nonce: String, now: Long): Boolean {
        val it = seen.entries.iterator()
        while (it.hasNext()) if (now - it.next().value > keepMs) it.remove()
        if (seen.containsKey(nonce)) return false
        if (seen.size >= max) seen.remove(seen.keys.first())
        seen[nonce] = now
        return true
    }
}
