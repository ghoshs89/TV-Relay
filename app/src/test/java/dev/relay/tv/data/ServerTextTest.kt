package dev.relay.tv.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerTextTest {
    private val h = "http://a.example.com"
    private val blank = HostStatus(h, null, null, null, null, null)
    private val now = 10_000_000L

    @Test fun notTried() = assertEquals("not tried since Relay started", ServerText.status(blank, now))

    @Test fun answered() {
        assertEquals("answered in 240 ms 2 min ago", ServerText.status(blank.copy(lastOkAt = now - 125_000, lastOkMs = 240), now))
    }

    @Test fun coolingDown() {
        val st = blank.copy(lastFailAt = now - 30_000, lastError = "HTTP 502", coolingUntil = now + 570_000)
        assertEquals("skipped for 10 min, failed just now: HTTP 502", ServerText.status(st, now))
    }

    @Test fun failedAfterAnsweringAndNoLongerCooling() {
        val st = blank.copy(lastOkAt = now - 7_200_000, lastOkMs = 90, lastFailAt = now - 1_200_000, lastError = "timeout", coolingUntil = now - 600_000)
        assertEquals("failed 20 min ago: timeout; before that answered in 90 ms 2 h ago", ServerText.status(st, now))
    }

    @Test fun answeredAfterFailing() {
        val st = blank.copy(lastOkAt = now - 60_000, lastOkMs = 300, lastFailAt = now - 120_000, lastError = "x")
        assertEquals("answered in 300 ms 1 min ago", ServerText.status(st, now))
    }

    @Test fun ago() {
        assertEquals("just now", ServerText.ago(-5))
        assertEquals("3 d ago", ServerText.ago(3 * 86_400_000L + 5))
    }

    @Test fun marksCycle() {
        assertEquals(HostNet.NO_VPN, HostNet.ANY.next())
        assertEquals(HostNet.VPN, HostNet.NO_VPN.next())
        assertEquals(HostNet.ANY, HostNet.VPN.next())
    }
}
