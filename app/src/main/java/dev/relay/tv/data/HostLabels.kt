package dev.relay.tv.data

/**
 * Reads which network each server in a provider's message is meant for, from the words in front of
 * it ("backup url without vpn = http://…", "if you use vpn please use = http://…"). Pure, so it is
 * unit tested on a computer; ProviderText cuts the message into one label per server.
 */
object HostLabels {
    private val NOT_VPN = listOf("without vpn", "no vpn", "non vpn", "non-vpn", "w/o vpn", "not using vpn", "without a vpn", "not on vpn")
    private val VPN_WORD = Regex("""\bvpn\b""")
    private val SPACES = Regex("""\s+""")

    /** One mark per label. [labels]: for each server in message order, the text between the previous server's URL (or the start) and its own. */
    fun tags(labels: List<String>): List<HostNet> {
        val first = labels.map { raw ->
            val l = raw.lowercase().replace(SPACES, " ")
            when {
                NOT_VPN.any { it in l } -> HostNet.NO_VPN
                VPN_WORD.containsMatchIn(l) -> HostNet.VPN
                else -> HostNet.ANY
            }
        }
        // A provider that marks some servers "for VPN" means the unmarked ones are its normal servers.
        return if (HostNet.VPN in first) first.map { if (it == HostNet.ANY) HostNet.NO_VPN else it } else first
    }
}
