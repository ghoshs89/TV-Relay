package dev.relay.tv.data

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProviderAndLogSecurityTest {
    @get:Rule val files = TemporaryFolder()

    @Test
    fun providerTextExtractsStreamHostsAndSkipsPortalSupportAndEpgLinks() {
        val welcome = """
            Welcome to Example TV!
            Main URL without VPN: http://main.provider.example:8080/
            Backup URL with VPN = https://vpn.provider.example:8443/player_api.php?username=alice&password=secret123#frag
            EPG / XMLTV: http://epg.provider.example/guide.xml
            Billing Panel: https://billing.provider.example
            Support: https://support.provider.example
        """.trimIndent()
        val parsed = ProviderText.parse(welcome)
        assertEquals(
            listOf("http://main.provider.example:8080", "https://vpn.provider.example:8443"),
            parsed.hosts,
        )
        assertEquals("alice", parsed.username)
        assertEquals("secret123", parsed.password)
        assertEquals(HostNet.NO_VPN, parsed.nets["http://main.provider.example:8080"])
        assertEquals(HostNet.VPN, parsed.nets["https://vpn.provider.example:8443"])
    }

    @Test
    fun normalizeHostStripsFragmentsAndRefusesLoopbackByDefault() {
        assertEquals("http://10.0.2.2:8099", XtreamApi.normalizeHost("http://10.0.2.2:8099/player_api.php?x=1#top"))
        assertEquals("https://tv.example.com", XtreamApi.normalizeHost("https://tv.example.com:443/#hash"))
        assertEquals("", XtreamApi.normalizeHost("http://localhost:8080"))
        assertEquals("", XtreamApi.normalizeHost("http://127.0.0.1:8080"))
        assertEquals("", XtreamApi.normalizeHost("http://0.0.0.0:8080"))
        assertEquals("", XtreamApi.normalizeHost("http://[::1]:8080"))
        assertEquals("http://127.0.0.1:8080", XtreamApi.normalizeHost("http://127.0.0.1:8080", allowLoopback = true))
    }

    @Test
    fun downloadFailsOverToBackupWhenPrimaryReturnsHtmlCaptivePage() = runBlocking {
        MockWebServer().use { primary ->
            MockWebServer().use { backup ->
                primary.enqueue(MockResponse().setResponseCode(200).setBody("<html><body>Captive portal</body></html>"))
                backup.enqueue(MockResponse().setResponseCode(200).setBody("""[{"stream_id":1,"name":"BBC One"}]"""))
                val api = XtreamApi(OkHttpClient()) { "RelayTest" }.apply {
                    account = Account(
                        "Test",
                        listOf(primary.url("/").toString().trimEnd('/'), backup.url("/").toString().trimEnd('/')),
                        "demo",
                        "demo",
                    )
                }
                val dest = files.newFile("channels.json")
                val res = api.download("get_live_streams", dest)
                assertEquals(backup.hostName, res.host)
                assertTrue(dest.readText().startsWith("["))
            }
        }
    }

    @Test
    fun authenticateFailsOverOnPortalJsonWithoutUserInfo() = runBlocking {
        MockWebServer().use { primary ->
            MockWebServer().use { backup ->
                primary.enqueue(MockResponse().setResponseCode(200).setBody("""{"maintenance":true}"""))
                backup.enqueue(MockResponse().setResponseCode(200).setBody("""{"user_info":{"auth":1,"status":"Active","exp_date":"1893456000"}}"""))
                val api = XtreamApi(OkHttpClient()) { "RelayTest" }.apply {
                    account = Account(
                        "Test",
                        listOf(primary.url("/").toString().trimEnd('/'), backup.url("/").toString().trimEnd('/')),
                        "demo",
                        "demo",
                    )
                }
                val info = api.authenticate()
                assertEquals("Active", info.status)
                assertEquals(backup.url("/").toString().trimEnd('/'), api.host)
            }
        }
    }

    @Test
    fun appLogRedactsUrlEncodedXtreamPathAndQueryCredentials() {
        val prev = AppLog.secrets
        try {
            AppLog.secrets = { listOf("rawUser", "raw#Pass!") }
            val line = "Failed http://tv.example:8080/live/rawUser/raw%23Pass%21/404.ts and " +
                "http://tv.example:8080/timeshift/enc%40user/enc%2Fpass/60/2026-11-26:12-00/99.ts " +
                "and xmltv.php?username=enc%40user&password=enc%2Fpass"
            val scrubbed = AppLog.redact(line)
            assertFalse(scrubbed.contains("raw%23Pass%21"))
            assertFalse(scrubbed.contains("enc%2Fpass"))
            assertFalse(scrubbed.contains("enc%40user"))
            assertTrue(scrubbed.contains("/live/***/***/404.ts"))
            assertTrue(scrubbed.contains("/timeshift/***/***/60/"))
            assertTrue(scrubbed.contains("username=***&password=***"))
        } finally {
            AppLog.secrets = prev
        }
    }
}
