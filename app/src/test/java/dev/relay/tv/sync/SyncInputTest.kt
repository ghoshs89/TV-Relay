package dev.relay.tv.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SyncInputTest {
    @Test fun theAppRepositoryCannotBeUsedForSync() {
        val app = "ghoshs89/TV-Relay"
        for (input in listOf(app, "https://github.com/GHOSHS89/tv-relay.GIT/", "git@github.com:ghoshs89/TV-Relay.git")) {
            org.junit.Assert.assertNotNull(SyncInput.repositoryProblem(checkNotNull(Repo.parse(input)), app))
        }
        org.junit.Assert.assertNull(SyncInput.repositoryProblem(Repo("ghoshs89", "relay-sync"), app))
    }

    private val good = "github_pat_11ABCDEFG0abcdefghijkl_mnopqrstuvwxyz0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ012345"

    @Test fun plainTokenPasses() = assertEquals(good, SyncInput.token(good))

    @Test fun pastedExtrasAreRemoved() {
        assertEquals(good, SyncInput.token("  \"$good\"  "))
        assertEquals(good, SyncInput.token("Bearer $good"))
        assertEquals(good, SyncInput.token("token $good\n"))
        assertEquals(good, SyncInput.token(good.chunked(20).joinToString("\n")))
    }

    @Test fun classicTokenPasses() {
        assertNotNull(SyncInput.token("ghp_" + "a1B2".repeat(9)))
    }

    @Test fun nonsenseIsRefused() {
        assertNull(SyncInput.token(""))
        assertNull(SyncInput.token("abc"))
        assertNull(SyncInput.token("not a token with spaces and é and more than thirty characters"))
        assertNull(SyncInput.token("x".repeat(300)))
        assertNull(SyncInput.token("github_pat_" + "a".repeat(20) + "!!!!"))
    }

    @Test fun problemsAreExplained() {
        assertNull(SyncInput.problem("me/relay-sync", good))
        assertEquals(true, SyncInput.problem("nonsense", good)!!.contains("owner/name"))
        assertEquals(true, SyncInput.problem("me/relay-sync", "short")!!.contains("GitHub token"))
    }
}
