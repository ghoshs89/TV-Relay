package dev.relay.tv.sync

import dev.relay.tv.data.PlayItem
import dev.relay.tv.data.SyncSeal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PlayCommandTest {
    private val household = SyncSeal.newKey()
    private val now = 1_800_000_000_000L

    private fun cmd(
        kind: String = PlayCommand.MOVIE, id: String = "42", sentAt: Long = now, seriesId: Int? = null,
        ext: String = "", nonce: String = PlayCommand.newNonce(),
    ) = PlayCommand(from = "dev1", nonce = nonce, sentAt = sentAt, kind = kind, id = id, seriesId = seriesId, positionMs = 2_530_000, ext = ext, title = "A film")

    private fun rejected(block: () -> Unit): String {
        try {
            block()
        } catch (e: PlayRejected) {
            return e.message.orEmpty()
        }
        fail("expected the command to be refused")
        return ""
    }

    @Test
    fun aSealedCommandOpensUnchangedWithTheSameHouseholdKey() {
        val c = cmd()
        assertEquals(c, PlayCodec.open(PlayCodec.seal(c, household), household, now + 1_000))
    }

    @Test
    fun anotherHouseholdCannotOpenIt() {
        val sealed = PlayCodec.seal(cmd(), household)
        assertTrue(rejected { PlayCodec.open(sealed, SyncSeal.newKey(), now) }.contains("same sync household"))
    }

    @Test
    fun theSyncFileKeyItselfDoesNotOpenIt() {
        // Play commands use a key worked out from the household key, never the household key itself.
        val sealed = PlayCodec.seal(cmd(), household)
        try {
            SyncSeal.open(sealed, household)
            fail("the household key must not open a play command")
        } catch (_: Exception) {
        }
    }

    @Test
    fun aChangedOrCutMessageIsRefused() {
        val sealed = PlayCodec.seal(cmd(), household)
        val flipped = sealed.copyOf().also { it[it.size - 5] = (it[it.size - 5].toInt() xor 1).toByte() }
        rejected { PlayCodec.open(flipped, household, now) }
        rejected { PlayCodec.open(sealed.copyOf(sealed.size - 10), household, now) }
        rejected { PlayCodec.open(ByteArray(0), household, now) }
    }

    @Test
    fun anOldOrFutureCommandIsRefused() {
        val tooOld = PlayCodec.seal(cmd(sentAt = now - PlayCodec.MAX_AGE_MS - 1), household)
        val tooNew = PlayCodec.seal(cmd(sentAt = now + PlayCodec.MAX_AGE_MS + 1), household)
        assertTrue(rejected { PlayCodec.open(tooOld, household, now) }.contains("clocks"))
        rejected { PlayCodec.open(tooNew, household, now) }
        // Within the window either way is fine (two screens' clocks are rarely exact).
        PlayCodec.open(PlayCodec.seal(cmd(sentAt = now - 60_000), household), household, now)
        PlayCodec.open(PlayCodec.seal(cmd(sentAt = now + 60_000), household), household, now)
    }

    @Test
    fun malformedContentIsRefused() {
        rejected { PlayCodec.open(PlayCodec.seal(cmd(kind = "shell"), household), household, now) }
        rejected { PlayCodec.open(PlayCodec.seal(cmd(id = ""), household), household, now) }
        rejected { PlayCodec.open(PlayCodec.seal(cmd(kind = PlayCommand.EPISODE, seriesId = null), household), household, now) }
        rejected { PlayCodec.open(PlayCodec.seal(cmd(ext = "m/p4"), household), household, now) }
        rejected { PlayCodec.open(PlayCodec.seal(cmd(nonce = "short"), household), household, now) }
        rejected { PlayCodec.open(PlayCodec.seal(cmd().copy(v = 2), household), household, now) }
        rejected { PlayCodec.open(ByteArray(PlayCommand.MAX_BYTES + 1), household, now) }
    }

    @Test
    fun theSameCommandCannotBeUsedTwice() {
        val guard = ReplayGuard()
        assertTrue(guard.firstTime("n1", now))
        assertFalse(guard.firstTime("n1", now + 1_000))
        assertTrue(guard.firstTime("n2", now + 1_000))
        // Long after it could have passed the age check, the nonce is forgotten.
        assertTrue(guard.firstTime("n1", now + 3 * PlayCodec.MAX_AGE_MS))
    }

    @Test
    fun theReplayGuardStaysSmall() {
        val guard = ReplayGuard(max = 3)
        (1..10).forEach { assertTrue(guard.firstTime("n$it", now)) }
        assertTrue(guard.firstTime("n1", now)) // pushed out by newer ones
    }

    @Test
    fun theTagIsStableShortAndTellsHouseholdsApart() {
        val tag = PlayKeys.tag(household)
        assertEquals(8, tag.length)
        assertEquals(tag, PlayKeys.tag(household.copyOf()))
        assertNotEquals(tag, PlayKeys.tag(SyncSeal.newKey()))
        assertFalse(SyncSeal.keyToText(household).contains(tag))
    }

    @Test
    fun noStreamAddressOrLoginIsSent() {
        val item = PlayItem(
            key = "movie:42", kind = "movie", title = "A film", streamId = "42", ext = "mkv",
            urls = listOf("http://provider.example/movie/user/secretpass/42.mkv"),
        )
        val c = PlayOnTvClient.commandFor(item, 90_000, "dev1", now)!!
        val json = dev.relay.tv.data.Jsons.json.encodeToString(PlayCommand.serializer(), c)
        assertFalse(json.contains("secretpass"))
        assertFalse(json.contains("http"))
        assertEquals(90_000, c.positionMs)
    }

    @Test
    fun whatAPlayingItemBecomes() {
        val live = PlayItem(key = "live:7", kind = "live", title = "News", streamId = "7", ext = "ts")
        assertEquals(0L, PlayOnTvClient.commandFor(live, 55_000, "d", now)!!.positionMs)
        val ep = PlayItem(key = "ep:9", kind = "episode", title = "Show", streamId = "9", ext = "mkv", seriesId = 3)
        PlayOnTvClient.commandFor(ep, 1_000, "d", now)!!.let {
            assertEquals(PlayCommand.EPISODE, it.kind); assertEquals(3, it.seriesId); assertEquals("mkv", it.ext)
        }
        assertNull(PlayOnTvClient.commandFor(ep.copy(seriesId = null), 0, "d", now))
        assertNull(PlayOnTvClient.commandFor(PlayItem(key = "catchup:1:2", kind = "catchup", title = "x", streamId = "1", ext = "ts"), 0, "d", now))
    }
}
