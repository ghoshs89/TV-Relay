package dev.relay.tv.data

import dev.relay.tv.data.PlaybackDiagnosis.Login
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosisTest {
    private val now = 2_000_000_000L
    private fun ok(status: String = "Active", exp: Long? = now + 86_400, max: Int? = 2, active: Int? = 0) =
        Login.Ok(AccountInfo(status, exp, max, active), now)

    @Test
    fun streamLimitIsReportedWithCounts() {
        val r = PlaybackDiagnosis.diagnose(403, "ERROR_CODE_IO_BAD_HTTP_STATUS", ok(active = 2), live = true)
        assertEquals("All your streams are in use (2 of 2)", r.title)
    }

    @Test
    fun accountProblemsComeFirst() {
        assertEquals("Your login was rejected", PlaybackDiagnosis.diagnose(null, "STALL", Login.Rejected("x"), true).title)
        assertEquals("Can't reach your provider", PlaybackDiagnosis.diagnose(null, "STALL", Login.Unreachable, true).title)
        assertEquals("Your subscription has expired", PlaybackDiagnosis.diagnose(null, "STALL", ok(exp = now - 10), false).title)
        assertEquals("Your subscription is Banned", PlaybackDiagnosis.diagnose(null, "STALL", ok(status = "Banned"), false).title)
    }

    @Test
    fun streamErrorsInPlainWords() {
        assertEquals("This channel isn't available right now", PlaybackDiagnosis.diagnose(404, "X", ok(), true).title)
        assertEquals("The server refused this stream", PlaybackDiagnosis.diagnose(403, "X", ok(), true).title)
        assertTrue(PlaybackDiagnosis.diagnose(503, "X", ok(), false).title.contains("503"))
        assertEquals(
            "This device can't play this format",
            PlaybackDiagnosis.diagnose(null, "ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED", ok(), true).title,
        )
        assertEquals("This channel isn't responding", PlaybackDiagnosis.diagnose(null, "STALL", ok(), true).title)
    }
}
