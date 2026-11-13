package dev.relay.tv.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileFoldTest {
    private fun p(id: String, name: String = "Me", kids: Boolean = false, pin: String? = null) = Profile(id = id, name = name, kids = kids, pin = pin)

    @Test fun aFreshInstallsMeBecomesTheHouseholdsMe() {
        val theirs = p("home")
        assertEquals(mapOf("new" to theirs), ProfileFold.plan(listOf(p("new")), listOf(theirs)))
    }

    @Test fun nameMatchIgnoresCaseAndSpaces() {
        val theirs = p("home", name = "Me")
        assertEquals(mapOf("new" to theirs), ProfileFold.plan(listOf(p("new", name = " me ")), listOf(theirs)))
    }

    @Test fun aProfileAlreadySharedIsLeftAlone() {
        assertEquals(emptyMap<String, Profile>(), ProfileFold.plan(listOf(p("home")), listOf(p("home"))))
    }

    @Test fun differentNamesOrKindsStaySeparate() {
        assertEquals(emptyMap<String, Profile>(), ProfileFold.plan(listOf(p("new", name = "Dad")), listOf(p("home"))))
        assertEquals(emptyMap<String, Profile>(), ProfileFold.plan(listOf(p("new", kids = true)), listOf(p("home"))))
    }

    @Test fun twoHouseholdProfilesOfThatNameIsUnclearSoNothingHappens() {
        assertEquals(emptyMap<String, Profile>(), ProfileFold.plan(listOf(p("new")), listOf(p("a"), p("b"))))
    }

    @Test fun twoProfilesHereOfThatNameIsUnclearSoNothingHappens() {
        assertEquals(emptyMap<String, Profile>(), ProfileFold.plan(listOf(p("n1"), p("n2")), listOf(p("home"))))
    }

    @Test fun aHouseholdProfileThisScreenAlreadyHasIsNotATarget() {
        // This screen has "home" already (shared) and a second "Me" of its own: the second must not fold into "home".
        assertEquals(emptyMap<String, Profile>(), ProfileFold.plan(listOf(p("home"), p("new")), listOf(p("home"))))
    }

    @Test fun aDifferentPinHereKeepsThemSeparateButAPinOnlyThereDoesNot() {
        assertEquals(emptyMap<String, Profile>(), ProfileFold.plan(listOf(p("new", pin = "1111")), listOf(p("home", pin = "2222"))))
        val locked = p("home", pin = "2222")
        assertEquals(mapOf("new" to locked), ProfileFold.plan(listOf(p("new")), listOf(locked)))
        assertEquals(mapOf("new" to locked), ProfileFold.plan(listOf(p("new", pin = "2222")), listOf(locked)))
    }

    @Test fun severalProfilesEachFindTheirOwn() {
        val me = p("h1")
        val kid = p("h2", name = "Sam", kids = true)
        assertEquals(
            mapOf("n1" to me, "n2" to kid),
            ProfileFold.plan(listOf(p("n1"), p("n2", name = "Sam", kids = true), p("n3", name = "Guest")), listOf(me, kid)),
        )
    }

    @Test fun favouritesOfBothEachOnce() {
        val a = FavItem("movie", 1, "A")
        val b = FavItem("live", 1, "B")
        val a2 = FavItem("movie", 1, "A again")
        assertEquals(listOf(a, b), ProfileFold.favs(listOf(a), listOf(b, a2)))
    }

    @Test fun historyNewestFirstEachTitleOnce() {
        fun e(key: String, at: Long, pos: Long = 0) = WatchEntry(key = key, kind = "movie", title = key, streamId = "1", ext = "mp4", position = pos, updated = at)
        val merged = ProfileFold.history(listOf(e("m:1", 100, 5), e("m:2", 50)), listOf(e("m:1", 300, 9), e("m:3", 200)))
        assertEquals(listOf("m:1", "m:3", "m:2"), merged.map { it.key })
        assertEquals(9L, merged[0].position)
        assertEquals(2, ProfileFold.history(listOf(e("a", 1), e("b", 2)), listOf(e("c", 3)), max = 2).size)
    }
}
