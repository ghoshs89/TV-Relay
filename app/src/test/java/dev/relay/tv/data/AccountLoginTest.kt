package dev.relay.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountLoginTest {
    private val a = Account("Home", listOf("http://a.example.com", "http://b.example.com"), "u", "p")

    @Test fun marksAndNameDoNotChangeTheLogin() {
        val marked = a.copy(name = "Other", hostNets = mapOf("http://b.example.com" to "vpn"))
        assertTrue(a.sameLogin(marked))
        assertTrue(marked.sameLogin(a))
    }

    @Test fun serverOrderDoesNotChangeTheLogin() {
        assertTrue(a.sameLogin(a.copy(hosts = a.hosts.reversed())))
    }

    @Test fun userPasswordOrServersDo() {
        assertFalse(a.sameLogin(null))
        assertFalse(a.sameLogin(a.copy(username = "v")))
        assertFalse(a.sameLogin(a.copy(password = "q")))
        assertFalse(a.sameLogin(a.copy(hosts = a.hosts + "http://c.example.com")))
        assertFalse(a.sameLogin(a.copy(hosts = a.hosts.take(1))))
    }

    @Test fun netOfReadsMarksAndDefaultsToAny() {
        val m = a.copy(hostNets = mapOf("http://a.example.com" to "no_vpn", "http://b.example.com" to "something-new"))
        assertEquals(HostNet.NO_VPN, m.netOf("http://a.example.com"))
        assertEquals(HostNet.ANY, m.netOf("http://b.example.com"))
        assertEquals(HostNet.ANY, m.netOf("http://unknown.example.com"))
    }

    @Test fun codesRoundTrip() {
        for (n in HostNet.entries) assertEquals(n, HostNet.of(n.code))
        assertEquals(HostNet.ANY, HostNet.of(null))
    }

    @Test fun netsForKeepsOnlySavedServersAndRealMarks() {
        val nets = mapOf(
            "http://a.example.com" to HostNet.VPN,
            "http://b.example.com" to HostNet.ANY,
            "http://gone.example.com" to HostNet.NO_VPN,
        )
        assertEquals(mapOf("http://a.example.com" to "vpn"), Account.netsFor(a.hosts, nets))
    }
}
