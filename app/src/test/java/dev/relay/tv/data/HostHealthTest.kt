package dev.relay.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

class HostHealthTest {
    private var t = 5_000L
    private val h = HostHealth { t }
    private val a = "http://a.example.com"
    private val b = "http://b.example.com"

    @Test fun aFailureCoolsDownForTenMinutes() {
        h.failed(a, IOException("HTTP 502 from a.example.com"))
        assertEquals(mapOf(a to 5_000L + HostHealth.COOL_MS), h.coolingUntil())
        val s = h.snapshot(listOf(a)).single()
        assertEquals(5_000L, s.lastFailAt)
        assertEquals("HTTP 502 from a.example.com", s.lastError)
    }

    @Test fun answeringEndsTheCoolDownAndKeepsTheLastFailure() {
        h.failed(a, null)
        t += 1_000
        h.answered(a, 240)
        assertEquals(emptyMap<String, Long>(), h.coolingUntil())
        val s = h.snapshot(listOf(a)).single()
        assertEquals(6_000L, s.lastOkAt)
        assertEquals(240L, s.lastOkMs)
        assertEquals(5_000L, s.lastFailAt)
        assertEquals("failed", s.lastError)
    }

    @Test fun snapshotListsEveryServerAskedFor() {
        h.answered(b, 90)
        val s = h.snapshot(listOf(a, b))
        assertEquals(listOf(a, b), s.map { it.host })
        assertNull(s[0].lastOkAt)
        assertEquals(90L, s[1].lastOkMs)
    }

    @Test fun forget() {
        h.failed(a, null)
        h.forget()
        assertEquals(emptyMap<String, Long>(), h.coolingUntil())
    }

    @Test fun describeKeepsOneShortLine() {
        assertEquals("first", HostHealth.describe(IOException("first\nsecond")))
        assertEquals("SocketTimeoutException", HostHealth.describe(java.net.SocketTimeoutException()))
        val long = HostHealth.describe(IOException("x".repeat(200)))
        assertEquals(60, long.length)
    }

    @Test fun notReachingTheServerAlwaysCounts() {
        for (a in listOf(null, "get_live_streams", "get_short_epg")) {
            assertEquals(true, HostHealth.isServerFailure(a, HostHealth.CONNECTING, 0))
        }
    }

    @Test fun anErrorStatusCountsForLoginAndListsButNotForOneChannelOrTitle() {
        assertEquals(true, HostHealth.isServerFailure(null, HostHealth.STATUS, 403))
        assertEquals(true, HostHealth.isServerFailure("get_vod_streams", HostHealth.STATUS, 404))
        for (a in listOf("get_short_epg", "get_simple_data_table", "get_vod_info", "get_series_info")) {
            assertEquals(false, HostHealth.isServerFailure(a, HostHealth.STATUS, 404))
            assertEquals(false, HostHealth.isServerFailure(a, HostHealth.STATUS, 403))
            assertEquals(true, HostHealth.isServerFailure(a, HostHealth.STATUS, 502))
        }
    }

    @Test fun aReplyThatCannotBeReadNeverCounts() {
        assertEquals(false, HostHealth.isServerFailure("get_short_epg", HostHealth.BODY, 200))
        assertEquals(false, HostHealth.isServerFailure("get_live_streams", HostHealth.BODY, 200))
        assertEquals(false, HostHealth.isServerFailure(null, HostHealth.BODY, 200))
    }
}
