package dev.relay.tv.data

/**
 * The order in which the provider's servers are tried, for API calls and for every stream URL list.
 * Pure, so it is unit tested on a computer.
 *
 * 1. A server suits the current network when it is marked for any network, for use with a VPN while
 *    one is on, or for use without one while none is. With [vpnOn] unknown (null) every server suits.
 * 2. Suitable servers come first, then the rest as a last resort (a "with VPN" server may still answer
 *    without one). Each group keeps the saved order, except that the server that answered last
 *    ([sticky]) leads its group and servers cooling down after a failure go to the end of theirs.
 * 3. Every saved server appears exactly once.
 *
 * With no marks, a known network and nothing cooling down this is exactly the old order: the sticky
 * server, then the others as saved.
 */
object HostOrder {
    fun suits(net: HostNet, vpnOn: Boolean?): Boolean = when {
        vpnOn == null || net == HostNet.ANY -> true
        net == HostNet.VPN -> vpnOn
        else -> !vpnOn
    }

    fun order(
        hosts: List<String>,
        netOf: (String) -> HostNet,
        vpnOn: Boolean?,
        sticky: String?,
        coolingUntil: Map<String, Long>,
        now: Long,
    ): List<String> {
        val unique = hosts.distinct()
        fun cooling(h: String) = (coolingUntil[h] ?: Long.MIN_VALUE) > now
        fun arrange(group: List<String>): List<String> {
            val lead = group.filter { it == sticky && !cooling(it) }
            val rest = group.filter { it != sticky && !cooling(it) }
            val cool = group.filter { cooling(it) }.let { c -> c.filter { it == sticky } + c.filter { it != sticky } }
            return lead + rest + cool
        }
        val (fit, other) = unique.partition { suits(netOf(it), vpnOn) }
        return arrange(fit) + arrange(other)
    }
}
