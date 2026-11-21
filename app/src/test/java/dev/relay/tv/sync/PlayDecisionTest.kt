package dev.relay.tv.sync

import dev.relay.tv.data.Channel
import dev.relay.tv.data.Movie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayDecisionTest {
    private fun ch(id: Int, cat: String) = Channel(id, id, "Ch $id", null, cat, null, 0)
    private val channels = listOf(ch(1, "news"), ch(2, "news"), ch(3, "news"), ch(9, "xxx"), Channel(20, 20, "Sports", null, "sport", null, 3), Channel(21, 21, "Adult rec", null, "xxx", null, 3))
    private val film = Movie(42, "A film", null, "films", 7.0, "mkv", 0L, null, "2020")
    private val adultFilm = film.copy(id = 43, categoryId = "xxx")

    private class Tv(
        val channels: List<Channel>, val films: List<Movie>,
        override val profileOpen: Boolean = true, override val libraryReady: Boolean = true,
        override val viewingBlocked: String? = null, val blocked: Set<String> = setOf("xxx"),
    ) : PlayLookups {
        override fun channel(id: Int) = channels.firstOrNull { it.id == id }
        override fun channelsIn(categoryId: String) = channels.filter { it.categoryId == categoryId }
        override fun movie(id: Int) = films.firstOrNull { it.id == id }
        override fun seriesCategory(id: Int) = if (id == 5) "shows" else if (id == 6) "xxx" else null
        override fun allows(categoryId: String) = categoryId !in blocked
    }

    private fun cmd(kind: String, id: String, seriesId: Int? = null, pos: Long = 0) =
        PlayCommand(from = "d", nonce = PlayCommand.newNonce(), sentAt = 0, kind = kind, id = id, seriesId = seriesId, positionMs = pos, ext = "mkv", title = "Thing")

    private val tv = Tv(channels, listOf(film, adultFilm))

    @Test
    fun aChannelTunesWithinItsCategory() {
        val d = PlayDecider.decide(cmd(PlayCommand.LIVE, "2"), tv, "Living room TV") as PlayDecision.Live
        assertEquals(listOf(1, 2, 3), d.channels.map { it.id })
        assertEquals(1, d.index)
    }

    @Test
    fun aFilmStartsWhereThePhoneWas() {
        val d = PlayDecider.decide(cmd(PlayCommand.MOVIE, "42", pos = 2_530_000), tv, "TV") as PlayDecision.Film
        assertEquals(42, d.movie.id)
        assertEquals(2_530_000, d.positionMs)
    }

    @Test
    fun anEpisodeResumesWithItsSeries() {
        val d = PlayDecider.decide(cmd(PlayCommand.EPISODE, "77", seriesId = 5, pos = 60_000), tv, "TV") as PlayDecision.Episode
        assertEquals("ep:77", d.entry.key)
        assertEquals(5, d.entry.seriesId)
        assertEquals(60_000, d.positionMs)
    }

    @Test
    fun theTvsProfileDecidesWhatIsAllowed() {
        for (c in listOf(cmd(PlayCommand.LIVE, "9"), cmd(PlayCommand.MOVIE, "43"), cmd(PlayCommand.EPISODE, "1", seriesId = 6))) {
            val d = PlayDecider.decide(c, tv, "Kids TV")
            assertTrue(d is PlayDecision.Refuse && d.reason.contains("profile open on Kids TV"))
        }
    }

    @Test
    fun somethingTheTvDoesNotHaveIsRefused() {
        for (c in listOf(cmd(PlayCommand.LIVE, "100"), cmd(PlayCommand.MOVIE, "x"), cmd(PlayCommand.EPISODE, "1", seriesId = 99))) {
            val d = PlayDecider.decide(c, tv, "TV")
            assertTrue(d is PlayDecision.Refuse && d.reason.contains("isn't in the library"))
        }
    }

    @Test
    fun noProfileNoLibraryOrTimeUpIsRefused() {
        val c = cmd(PlayCommand.MOVIE, "42")
        assertTrue((PlayDecider.decide(c, Tv(channels, listOf(film), profileOpen = false), "TV") as PlayDecision.Refuse).reason.startsWith("Pick a profile"))
        assertTrue((PlayDecider.decide(c, Tv(channels, listOf(film), libraryReady = false), "TV") as PlayDecision.Refuse).reason.contains("loading"))
        assertEquals("Bedtime", (PlayDecider.decide(c, Tv(channels, listOf(film), viewingBlocked = "Bedtime"), "TV") as PlayDecision.Refuse).reason)
    }

    private val nowSec = 1_800_000_000L
    private fun catchup(id: String, start: Long, pos: Long = 0) =
        PlayCommand(v = PlayCommand.VERSION_CATCHUP, from = "d", nonce = PlayCommand.newNonce(), sentAt = 0, kind = PlayCommand.CATCHUP, id = id, positionMs = pos, title = "Match", start = start, end = start + 3_600)

    @Test
    fun aRecordedProgrammePlaysFromThePhonesMinute() {
        val d = PlayDecider.decide(catchup("20", nowSec - 86_400, pos = 1_234_000), tv, "TV", nowSec) as PlayDecision.Catchup
        assertEquals(20, d.channel.id)
        assertEquals(nowSec - 86_400, d.programme.rawStart)
        assertEquals(nowSec - 86_400 + 3_600, d.programme.rawEnd)
        assertEquals(1_234_000, d.positionMs)
    }

    @Test
    fun catchUpIsRefusedWhereItCantPlay() {
        val noArchive = PlayDecider.decide(catchup("1", nowSec - 3_600), tv, "TV", nowSec)
        assertTrue(noArchive is PlayDecision.Refuse && noArchive.reason.contains("no catch-up"))
        val tooOld = PlayDecider.decide(catchup("20", nowSec - 5 * 86_400), tv, "TV", nowSec)
        assertTrue(tooOld is PlayDecision.Refuse && tooOld.reason.contains("no longer available"))
        val blocked = PlayDecider.decide(catchup("21", nowSec - 3_600), tv, "Kids TV", nowSec)
        assertTrue(blocked is PlayDecision.Refuse && blocked.reason.contains("profile open on Kids TV"))
        val missing = PlayDecider.decide(catchup("999", nowSec - 3_600), tv, "TV", nowSec)
        assertTrue(missing is PlayDecision.Refuse && missing.reason.contains("isn't in the library"))
    }
}
