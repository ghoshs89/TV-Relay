package dev.relay.tv.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionScopeTest {
    private val routes = listOf(
        "GET" to "", "POST" to "login", "POST" to "keys", "POST" to "sync", "POST" to "join",
        "GET" to "backup.json", "POST" to "restore", "GET" to "logs", "GET" to "library",
        "GET" to "api/library", "POST" to "api/library",
    )

    private fun served(scope: CompanionScope) = routes.filter { (m, a) -> scope.allows(m, a) }.toSet()

    @Test
    fun theFullPageServesEverything() {
        assertEquals(routes.toSet(), served(CompanionScope.FULL))
        assertTrue(CompanionScope.FULL.pairs)
    }

    @Test
    fun aKeysPageServesOnlyThePageAndKeys() {
        assertEquals(setOf("GET" to "", "POST" to "keys"), served(CompanionScope.KEYS))
        for (refused in listOf("GET" to "backup.json", "POST" to "restore", "POST" to "sync", "POST" to "login", "GET" to "logs")) {
            assertFalse("$refused", CompanionScope.KEYS.allows(refused.first, refused.second))
        }
        assertFalse(CompanionScope.KEYS.pairs)
    }

    @Test
    fun eachOtherPageServesOnlyItsOwnForm() {
        assertEquals(setOf("GET" to "", "POST" to "login"), served(CompanionScope.LOGIN))
        assertEquals(setOf("GET" to "", "POST" to "sync"), served(CompanionScope.SYNC))
        assertEquals(setOf("GET" to "", "POST" to "join"), served(CompanionScope.JOIN))
        assertTrue(CompanionScope.JOIN.pairs)
        assertFalse(CompanionScope.LOGIN.pairs)
        assertFalse(CompanionScope.SYNC.pairs)
    }

    @Test
    fun theRightMethodIsNeeded() {
        assertFalse(CompanionScope.KEYS.allows("GET", "keys"))
        assertFalse(CompanionScope.LOGIN.allows("POST", ""))
    }

    @Test
    fun thePageShowsOnlyTheSectionsItServes() {
        val k = CompanionScope.KEYS
        assertTrue(k.showsKeys)
        assertFalse(k.showsLogin || k.showsSync || k.showsJoin || k.showsRest)
        val f = CompanionScope.FULL
        assertTrue(f.showsLogin && f.showsKeys && f.showsSync && f.showsRest)
    }
}
