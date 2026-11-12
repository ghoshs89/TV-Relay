package dev.relay.tv.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.MessageDigest

/**
 * The saved library copy stores values Relay worked out (languages, version keys, quality, sort
 * keys, HD flags, adult category flags, years taken from titles). They are only right for the code
 * that computed them. This test runs that code over a fixed sample and compares a hash of the
 * answers with the one recorded for the current [LibrarySnapshot.LOGIC]. Change the rules and this
 * fails until LOGIC is bumped and the hash below is updated, so an update can never show values
 * the old rules made.
 */
class SnapshotLogicTest {

    /** The LOGIC value the hash below was recorded for. */
    private val recordedFor = 1
    private val recordedHash = "9d7ea8280177d1d6"

    private fun sampleHash(): String {
        val sb = StringBuilder()
        val names = TextCorpus.channelNames(2_000)
        val cats = TextCorpus.categoryLangs
        names.forEachIndexed { i, n ->
            val f = ChannelText.derive(n, cats[i % cats.size])
            sb.append(f.lang).append('|').append(f.versionKey).append('|').append(f.quality).append('|')
                .append(f.sortKey).append('|').append(f.hd).append('\n')
        }
        val titles = TextCorpus.titles(300) + TextCorpus.seedNames
        titles.forEachIndexed { i, t ->
            sb.append(LanguageDetector.ofTitle(t, cats[i % cats.size])).append('|').append(LibraryDerive.yearIn(t)).append('\n')
        }
        for (c in TextCorpus.categories()) {
            sb.append(LanguageDetector.ofCategory(c)).append('|').append(LibraryDerive.isAdult(c)).append('\n')
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(sb.toString().toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    @Test
    fun theHashAndTheLogicVersionGoTogether() {
        val actual = sampleHash()
        assertEquals(
            "LibrarySnapshot.LOGIC is ${LibrarySnapshot.LOGIC} but this test was recorded for $recordedFor. " +
                "Record the new hash ($actual) and set recordedFor to match.",
            recordedFor, LibrarySnapshot.LOGIC,
        )
        assertEquals(
            "Detection logic changed: bump LibrarySnapshot.LOGIC and update the hash in this test (new hash: $actual)",
            recordedHash, actual,
        )
    }

    @Test
    fun theSampleIsStable() {
        // Two runs agree, so a failure above means the rules changed and not the sample.
        assertEquals(sampleHash(), sampleHash())
    }
}
