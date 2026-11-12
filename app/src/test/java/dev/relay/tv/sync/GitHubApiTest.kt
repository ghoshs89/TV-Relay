package dev.relay.tv.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Random

class GitHubApiTest {

    private fun problem(block: () -> Unit): Problem {
        try {
            block()
        } catch (e: TransportException) {
            return e.problem
        }
        fail("expected a TransportException")
        throw IllegalStateException()
    }

    // ---------- Repo ----------

    @Test fun repoParses() {
        assertEquals(Repo("me", "relay-sync"), Repo.parse("me/relay-sync"))
        assertEquals(Repo("me", "relay-sync"), Repo.parse("  me/relay-sync/ "))
        assertEquals(Repo("me", "relay-sync"), Repo.parse("https://github.com/me/relay-sync"))
        assertEquals(Repo("me", "relay-sync"), Repo.parse("https://github.com/me/relay-sync.git"))
        assertEquals(Repo("me", "relay-sync"), Repo.parse("github.com/me/relay-sync/"))
        assertEquals(Repo("a.b", "c_d-e"), Repo.parse("a.b/c_d-e"))
        assertEquals("me/relay-sync", Repo.parse("me/relay-sync").toString())
    }

    @Test fun repoRefusesNonsense() {
        for (bad in listOf("", "me", "me/", "/repo", "a/b/c", "me/re po", "me/..", "../x", "https://example.com/me/repo", "me/repo?x=1", "me\\repo")) {
            assertNull("'$bad'", Repo.parse(bad))
        }
    }

    // ---------- base64 ----------

    @Test fun base64MatchesTheJdk() {
        val rnd = Random(7)
        for (len in 0..70) {
            val b = ByteArray(len).also { rnd.nextBytes(it) }
            val text = Base64Lite.encode(b)
            assertEquals(java.util.Base64.getEncoder().encodeToString(b), text)
            assertArrayEquals(b, Base64Lite.decode(text))
        }
        val big = ByteArray(100_001).also { rnd.nextBytes(it) }
        assertArrayEquals(big, Base64Lite.decode(Base64Lite.encode(big)))
    }

    @Test fun base64IgnoresLineBreaks() {
        val b = ByteArray(200) { it.toByte() }
        val wrapped = Base64Lite.encode(b).chunked(60).joinToString("\n") + "\n"
        assertArrayEquals(b, Base64Lite.decode(wrapped))
    }

    @Test fun base64RefusesDamage() {
        assertNull(Base64Lite.decode("abc"))
        assertNull(Base64Lite.decode("ab!d"))
        assertNull(Base64Lite.decode("=abc"))
        assertNull(Base64Lite.decode("ab=c"))
        assertNull(Base64Lite.decode("abcd=abc"))
    }

    // ---------- reading ----------

    @Test fun readGivesBytesAndSha() {
        val bytes = ByteArray(300) { (it * 3).toByte() }
        val body = """{"name":"relay-sync.bin","sha":"abc123","size":300,"encoding":"base64","content":"${Base64Lite.encode(bytes).chunked(60).joinToString("\\n")}\n"}"""
        val r = GitHubApi.readResult(200, body, "4000")!!
        assertEquals("abc123", r.version)
        assertArrayEquals(bytes, r.bytes)
    }

    @Test fun missingFileIsNull() {
        assertNull(GitHubApi.readResult(404, """{"message":"Not Found"}""", null))
    }

    @Test fun readFailuresAreClassified() {
        assertEquals(Problem.Refused, problem { GitHubApi.readResult(401, "{}", null) })
        assertEquals(Problem.Refused, problem { GitHubApi.readResult(403, "{}", "59") })
        assertEquals(Problem.Other, problem { GitHubApi.readResult(403, "{}", "0") })
        assertEquals(Problem.Offline, problem { GitHubApi.readResult(502, "", null) })
        assertEquals(Problem.Other, problem { GitHubApi.readResult(418, """{"message":"teapot"}""", null) })
    }

    @Test fun bigFileHasNoContent() {
        assertEquals(Problem.TooLarge, problem { GitHubApi.readResult(200, """{"sha":"x","content":"","encoding":"none"}""", null) })
    }

    @Test fun oddReplies() {
        assertEquals(Problem.Other, problem { GitHubApi.readResult(200, "not json", null) })
        assertEquals(Problem.Other, problem { GitHubApi.readResult(200, """{"content":"AAAA"}""", null) })
        assertEquals(Problem.Other, problem { GitHubApi.readResult(200, """{"sha":"x","content":"A!AA"}""", null) })
    }

    // ---------- writing ----------

    @Test fun putBodyCarriesContentAndSha() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        val withSha = GitHubApi.putBody(bytes, "sha1", "Relay sync")
        assertTrue(withSha.contains("\"sha\":\"sha1\""))
        assertTrue(withSha.contains("\"content\":\"${Base64Lite.encode(bytes)}\""))
        assertTrue(withSha.contains("\"message\":\"Relay sync\""))
        assertFalse(GitHubApi.putBody(bytes, null, "x").contains("\"sha\""))
    }

    @Test fun savedGivesTheNewSha() {
        val w = GitHubApi.writeResult(200, """{"content":{"name":"relay-sync.bin","sha":"new1"},"commit":{"sha":"c"}}""", null)
        assertEquals("new1", (w as WriteResult.Saved).version)
        val c = GitHubApi.writeResult(201, """{"content":{"sha":"new2"}}""", null)
        assertEquals("new2", (c as WriteResult.Saved).version)
    }

    @Test fun collisionsMeanChanged() {
        assertEquals(WriteResult.Changed, GitHubApi.writeResult(409, "{}", null))
        assertEquals(WriteResult.Changed, GitHubApi.writeResult(422, """{"message":"sha wasn't supplied"}""", null))
    }

    @Test fun writeFailuresAreClassified() {
        assertEquals(Problem.NotFound, problem { GitHubApi.writeResult(404, "{}", null) })
        assertEquals(Problem.Refused, problem { GitHubApi.writeResult(401, "{}", null) })
        assertEquals(Problem.Refused, problem { GitHubApi.writeResult(403, "{}", "10") })
        assertEquals(Problem.TooLarge, problem { GitHubApi.writeResult(413, "{}", null) })
        assertEquals(Problem.Offline, problem { GitHubApi.writeResult(503, "{}", null) })
        assertEquals(Problem.Other, problem { GitHubApi.writeResult(200, "{}", null) })
    }

    @Test fun wordingNamesTheFix() {
        val e = try {
            GitHubApi.readResult(403, "{}", "5")
            null
        } catch (e: TransportException) {
            e
        }!!
        assertTrue(e.message!!.contains("Contents"))
    }

    // ---------- the set-up check ----------

    private val repo = Repo("me", "relay-sync")

    @Test fun goodPrivateWritableRepoPasses() {
        assertNull(GitHubApi.repoCheck(repo, 200, """{"private":true,"permissions":{"push":true,"pull":true}}""", null))
    }

    @Test fun publicRepoIsRefused() {
        assertTrue(GitHubApi.repoCheck(repo, 200, """{"private":false,"permissions":{"push":true}}""", null)!!.contains("public"))
    }

    @Test fun readOnlyTokenIsRefused() {
        assertTrue(GitHubApi.repoCheck(repo, 200, """{"private":true,"permissions":{"push":false}}""", null)!!.contains("not write"))
    }

    @Test fun missingRepoAndBadTokenAreExplained() {
        assertNotNull(GitHubApi.repoCheck(repo, 404, "{}", null))
        assertTrue(GitHubApi.repoCheck(repo, 401, "{}", null)!!.contains("refused"))
        assertNotNull(GitHubApi.repoCheck(repo, 200, "nope", null))
    }

    @Test fun urls() {
        assertEquals("https://api.github.com/repos/me/relay-sync/contents/relay-sync.bin", GitHubApi.contentsUrl(repo))
        assertEquals("https://api.github.com/repos/me/relay-sync", GitHubApi.repoUrl(repo))
    }
}
