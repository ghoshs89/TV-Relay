package dev.relay.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostOrderTest {
    // Two servers for use without a VPN, then two for use with one, as a provider's message lists them.
    private val n1 = "http://n1.example.com"
    private val n2 = "http://n2.example.com"
    private val v1 = "http://v1.example.com"
    private val v2 = "http://v2.example.com"
    private val hosts = listOf(n1, n2, v1, v2)
    private val marks = mapOf(n1 to HostNet.NO_VPN, n2 to HostNet.NO_VPN, v1 to HostNet.VPN, v2 to HostNet.VPN)
    private val netOf: (String) -> HostNet = { marks[it] ?: HostNet.ANY }
    private val now = 1_000_000L

    private fun order(vpn: Boolean?, sticky: String? = null, cool: Map<String, Long> = emptyMap(), list: List<String> = hosts, nets: (String) -> HostNet = netOf) =
        HostOrder.order(list, nets, vpn, sticky, cool, now)

    @Test fun vpnOffPutsServersWithoutVpnFirst() = assertEquals(listOf(n1, n2, v1, v2), order(false))

    @Test fun vpnOnPutsVpnServersFirst() = assertEquals(listOf(v1, v2, n1, n2), order(true))

    @Test fun unknownNetworkKeepsSavedOrder() = assertEquals(hosts, order(null))

    @Test fun stickyLeadsItsGroupWhenItSuits() {
        assertEquals(listOf(v2, v1, n1, n2), order(true, sticky = v2))
    }

    @Test fun stickyThatDoesNotSuitLeadsTheLastResortGroup() {
        // Last answered without a VPN, VPN now on: the VPN servers go first, the old one leads the rest.
        assertEquals(listOf(v1, v2, n2, n1), order(true, sticky = n2))
    }

    @Test fun coolingServersMoveToTheEndOfTheirGroup() {
        assertEquals(listOf(n2, n1, v1, v2), order(false, cool = mapOf(n1 to now + 1)))
        assertEquals(listOf(n1, n2, v2, v1), order(false, cool = mapOf(v1 to now + 1)))
    }

    @Test fun aCoolingStickyServerIsNotFirst() {
        assertEquals(listOf(n2, n1, v1, v2), order(false, sticky = n1, cool = mapOf(n1 to now + 1)))
    }

    @Test fun anExpiredCoolDownCountsForNothing() {
        assertEquals(listOf(n1, n2, v1, v2), order(false, cool = mapOf(n1 to now, n2 to now - 5)))
    }

    @Test fun everythingCoolingStillListsEveryServer() {
        val all = hosts.associateWith { now + 10 }
        assertEquals(listOf(n1, n2, v1, v2), order(false, cool = all))
        assertEquals(listOf(v1, v2, n1, n2), order(true, cool = all))
    }

    @Test fun noMarksIsTheOldOrder() {
        val any: (String) -> HostNet = { HostNet.ANY }
        assertEquals(hosts, order(true, nets = any))
        assertEquals(listOf(v1, n1, n2, v2), order(false, sticky = v1, nets = any))
    }

    @Test fun unknownStickyAndCoolDownsAreIgnored() {
        assertEquals(listOf(n1, n2, v1, v2), order(false, sticky = "http://gone.example.com", cool = mapOf("http://gone.example.com" to now + 1)))
    }

    @Test fun oneServerAndNoServers() {
        assertEquals(listOf(v1), order(false, list = listOf(v1)))
        assertEquals(listOf(v1), order(false, list = listOf(v1), cool = mapOf(v1 to now + 1)))
        assertEquals(emptyList<String>(), order(true, list = emptyList()))
    }

    @Test fun duplicatesAppearOnce() {
        assertEquals(listOf(n1, v1), order(false, list = listOf(n1, v1, n1)))
    }

    @Test fun suits() {
        assertTrue(HostOrder.suits(HostNet.ANY, true))
        assertTrue(HostOrder.suits(HostNet.ANY, false))
        assertTrue(HostOrder.suits(HostNet.VPN, null))
        assertTrue(HostOrder.suits(HostNet.NO_VPN, null))
        assertTrue(HostOrder.suits(HostNet.VPN, true))
        assertFalse(HostOrder.suits(HostNet.VPN, false))
        assertTrue(HostOrder.suits(HostNet.NO_VPN, false))
        assertFalse(HostOrder.suits(HostNet.NO_VPN, true))
    }
}
