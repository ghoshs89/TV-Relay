package dev.relay.tv.telemetry

import dev.relay.tv.data.AppLog
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticUploadTest {

    @Test
    fun scrubForUploadRemovesSecretsAndProviderAndGuideHostnames() {
        val prev = AppLog.secrets
        try {
            AppLog.secrets = { listOf("friendUser", "friendPass99", "github_pat_secretToken") }
            val raw = """
                Relay 1.0.140 (140)
                Device: Google Chromecast, Android 12 (API 31)
                Servers saved: 2 (1 without VPN, 1 with VPN); in use http://vip.secret-iptv.example:8080
                  http://vip.secret-iptv.example:8080 [no VPN] ok 45ms
                  https://vpn.backup-provider.example:8443 [VPN] ok 90ms
                Failed http://vip.secret-iptv.example:8080/live/friendUser/friendPass99/101.ts
                Guide: http://epg.private-guide.example/xmltv.php?username=u&password=p
            """.trimIndent()

            val cleaned = DiagnosticUpload.scrubForUpload(
                rawText = raw,
                providerHosts = listOf("http://vip.secret-iptv.example:8080", "https://vpn.backup-provider.example:8443"),
                guideUrl = "http://epg.private-guide.example/guide.xml",
            )
            assertFalse(cleaned.contains("secret-iptv.example"))
            assertFalse(cleaned.contains("backup-provider.example"))
            assertFalse(cleaned.contains("private-guide.example"))
            assertFalse(cleaned.contains("friendUser"))
            assertFalse(cleaned.contains("friendPass99"))
            assertFalse(cleaned.contains("github_pat_secretToken"))
            assertTrue(cleaned.contains("<provider-server-1>"))
            assertTrue(cleaned.contains("<provider-server-2>"))
            assertTrue(cleaned.contains("<guide-server>"))
            assertTrue(cleaned.contains("Google Chromecast"))
        } finally {
            AppLog.secrets = prev
        }
    }

    @Test
    fun scrubForUploadTruncatesLargeLogsWhileKeepingHeaderAndTail() {
        val huge = buildString {
            append("Relay 1.0.140\n===== Last crash =====\nNullPointerException\n")
            repeat(5000) { append("Line $it: normal player buffer activity padding text\n") }
            append("FINAL LINE RIGHT BEFORE UPLOAD\n")
        }
        val clipped = DiagnosticUpload.scrubForUpload(huge, emptyList(), maxBytes = 16 * 1024)
        assertTrue(clipped.toByteArray(Charsets.UTF_8).size <= 16 * 1024)
        assertTrue(clipped.contains("NullPointerException"))
        assertTrue(clipped.contains("FINAL LINE RIGHT BEFORE UPLOAD"))
    }
}
