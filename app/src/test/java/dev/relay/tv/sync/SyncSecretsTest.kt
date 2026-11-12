package dev.relay.tv.sync

import dev.relay.tv.data.AppLog
import dev.relay.tv.data.SyncSeal
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The token and the household key must never reach a log line, whatever is logged around them. */
class SyncSecretsTest {
    private val token = "github_pat_11MADEUP0abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private val key = SyncSeal.keyToText(ByteArray(32) { (it * 9 + 2).toByte() })

    @After fun reset() {
        AppLog.secrets = { emptyList() }
    }

    @Test fun tokenAndKeyAreMasked() {
        AppLog.secrets = { listOf(token, key) }
        val line = AppLog.redact("PUT /repos/me/relay-sync/contents/relay-sync.bin with $token and key=$key failed")
        assertFalse(line, line.contains(token))
        assertFalse(line, line.contains(key))
        assertFalse(line, line.contains(token.take(20)))
    }

    @Test fun anAuthorizationHeaderIsMaskedEvenWithoutBeingRegistered() {
        AppLog.secrets = { emptyList() }
        val line = AppLog.redact("Authorization: Bearer $token")
        assertFalse(line, line.contains(token))
    }

    @Test fun errorTextsNeverCarryWhatWasSent() {
        val messages = mutableListOf<String>()
        for (code in listOf(401, 403, 404, 413, 418, 500, 503)) {
            try {
                GitHubApi.readResult(code, """{"message":"Bad credentials","token":"$token"}""", null)
            } catch (e: TransportException) {
                messages += e.message.orEmpty()
            }
            try {
                GitHubApi.writeResult(code, """{"message":"x","token":"$token","key":"$key"}""", null)
            } catch (e: TransportException) {
                messages += e.message.orEmpty()
            }
        }
        assertTrue(messages.isNotEmpty())
        for (m in messages) {
            assertFalse(m, m.contains(token))
            assertFalse(m, m.contains(key))
        }
    }

    @Test fun theRequestBodyHoldsOnlyTheSealedFile() {
        val sealed = SyncSeal.seal("hello".toByteArray(), SyncSeal.keyFromText(key)!!)
        val body = GitHubApi.putBody(sealed, null, "Relay sync")
        assertFalse(body.contains(token))
        assertFalse(body.contains(key))
        assertFalse(body.contains("hello"))
    }
}
