package dev.relay.tv.data

import org.junit.Assert.assertEquals
import org.junit.Test

class HostLabelsTest {
    private val any = HostNet.ANY
    private val no = HostNet.NO_VPN
    private val vpn = HostNet.VPN

    @Test fun providerMessageWithVpnAndBackups() {
        // The shape of a real welcome message: main server, backup without VPN, one for VPN, backup with VPN.
        val labels = listOf(
            "Please use Xstream code url/host = ",
            "\nbackup xstream url/host without vpn =\n",
            "\n\nif you use vpn please use this xstream code\nurl/host = ",
            "\nbackup xstream url/host with vpn =\n",
        )
        assertEquals(listOf(no, no, vpn, vpn), HostLabels.tags(labels))
    }

    @Test fun noVpnWordsLeavesEverythingAny() {
        assertEquals(listOf(any, any), HostLabels.tags(listOf("url = ", "backup url = ")))
    }

    @Test fun onlyWithoutVpnMarksLeaveTheRestAny() {
        assertEquals(listOf(any, no), HostLabels.tags(listOf("url = ", "backup without VPN: ")))
    }

    @Test fun negativeWordingsAllMeanNoVpn() {
        val l = listOf("no vpn", "Non-VPN server", "non vpn", "w/o VPN", "when NOT USING   vpn", "without a vpn")
        assertEquals(List(l.size) { no }, HostLabels.tags(l))
    }

    @Test fun vpnMustBeAWord() {
        // "vpnless" or a word that merely contains the letters is not a mark.
        assertEquals(listOf(any, any), HostLabels.tags(listOf("server vpnless =", "openvpnfree host")))
    }

    @Test fun caseAndLineBreaksDoNotMatter() {
        assertEquals(listOf(no, vpn), HostLabels.tags(listOf("Main", "IF YOU USE\nVPN")))
    }

    @Test fun empty() {
        assertEquals(emptyList<HostNet>(), HostLabels.tags(emptyList()))
    }
}
