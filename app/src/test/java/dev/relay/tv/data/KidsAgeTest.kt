package dev.relay.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KidsAgeTest {

    @Test
    fun ageTiersEnforceStrictCertificationGates() {
        assertEquals(6, KidsAge.classifyAgeBand("Toy Story", "Movies", certification = "G"))
        assertEquals(6, KidsAge.classifyAgeBand("Bluey", "Series", certification = "TV-Y"))
        assertEquals(9, KidsAge.classifyAgeBand("Avatar: The Last Airbender", "Series", certification = "TV-Y7"))
        assertEquals(12, KidsAge.classifyAgeBand("Inside Out", "Movies", certification = "PG"))
        assertEquals(12, KidsAge.classifyAgeBand("Avatar", "Series", certification = "TV-PG"))

        // Mature ratings blocked across every kids age tier (including 10-12 pre-teens).
        for (cert in listOf("PG-13", "TV-14", "R", "TV-MA", "NC-17", "A", "16+", "18+", "UA 13+")) {
            assertNull("Expected $cert to be blocked", KidsAge.classifyAgeBand("Some Show", "KIDS MOVIES", certification = cert))
            assertFalse("Expected $cert not eligible for maxAge=12", KidsAge.isKidsEligible("Some Show", "KIDS MOVIES", maxAge = 12, certification = cert))
        }
    }

    @Test
    fun maxAgeCapsYoungerProfilesCleanly() {
        // Little kids (0-6): G/preschool ok, TV-Y7 and PG blocked.
        assertTrue(KidsAge.isKidsEligible("Peppa Pig", "KIDS | PRESCHOOL", maxAge = 6))
        assertTrue(KidsAge.isKidsEligible("Finding Nemo", "ANIMATION", maxAge = 6, certification = "G"))
        assertFalse(KidsAge.isKidsEligible("SpongeBob", "CARTOONS", maxAge = 6, certification = "TV-Y7"))
        assertFalse(KidsAge.isKidsEligible("Frozen", "DISNEY", maxAge = 6, certification = "PG"))

        // Older kids (7-9): G and TV-Y7 ok, PG blocked.
        assertTrue(KidsAge.isKidsEligible("Peppa Pig", "KIDS | PRESCHOOL", maxAge = 9))
        assertTrue(KidsAge.isKidsEligible("Cartoon Classics", "KIDS MOVIES", maxAge = 9))
        assertFalse(KidsAge.isKidsEligible("Spider-Man", "FAMILY", maxAge = 9, certification = "PG"))

        // Pre-teens (10-12): G, TV-Y7, and PG allowed; PG-13 blocked.
        assertTrue(KidsAge.isKidsEligible("Spider-Man: Into the Spider-Verse", "FAMILY", maxAge = 12, certification = "PG"))
        assertFalse(KidsAge.isKidsEligible("The Dark Knight", "ACTION", maxAge = 12, certification = "PG-13"))
    }

    @Test
    fun matureGenresAndUnratedAdultCategoriesNeverLeak() {
        assertNull(KidsAge.classifyAgeBand("Oppenheimer", "ENGLISH MOVIES"))
        assertNull(KidsAge.classifyAgeBand("The Conjuring", "HORROR", certification = "G"))
        assertNull(KidsAge.classifyAgeBand("Crime Patrol", "KIDS & FAMILY", genres = listOf("Crime", "Documentary")))
        assertNull(KidsAge.classifyAgeBand("Scooby Horror Special", "CARTOON", genreIds = listOf(16, 27)))
        assertNull(KidsAge.classifyAgeBand("Attack on Titan", "ANIME"))
        // Certified safe anime with Kids/Family cert is allowed.
        assertEquals(6, KidsAge.classifyAgeBand("My Neighbor Totoro", "ANIME", certification = "G", genres = listOf("Animation", "Family")))
        assertFalse(KidsAge.isTmdbRecAllowedForKids(Tmdb.Rec("1", false, "Adult Film", adult = true)))
        assertFalse(KidsAge.isTmdbRecAllowedForKids(Tmdb.Rec("2", false, "Slasher", genreIds = listOf(27))))
        assertTrue(KidsAge.isTmdbRecAllowedForKids(Tmdb.Rec("3", false, "Pixar Film", genreIds = listOf(16, 10751))))
    }

    @Test
    fun profileEffectiveAgeDefaultsTo12AndHandlesBands() {
        assertEquals(12, Profile("p1", "Kid", kids = true).effectiveKidsAge())
        assertEquals(6, Profile("p1", "Toddler", kids = true, kidsMaxAge = 6).effectiveKidsAge())
        assertEquals(listOf("0-6"), KidsAge.eligibleBands(6))
        assertEquals(listOf("0-6", "7-9"), KidsAge.eligibleBands(9))
        assertEquals(listOf("0-6", "7-9", "10-12"), KidsAge.eligibleBands(12))
    }
}
