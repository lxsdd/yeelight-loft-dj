package io.github.lxsdd.yeelightloftdj

/**
 * Offline planner for normal device discovery.
 *
 * Normal discovery uses the official SSDP-like Yeelight UDP protocol only.
 * A full /24 TCP scan would open hundreds of sockets, including extra sockets
 * to already connected lamps. TCP fallback is intentionally reserved for
 * the user-visible advanced / deep-diagnostic action.
 */
object DiscoveryTrafficPolicy {
    data class NormalDiscovery(
        val multicast: List<YeelightDevice>,
        val directed: List<YeelightDevice>,
    )

    /**
     * The ordinary discovery API does not even accept a TCP-connection
     * callback: an accidental full subnet scan cannot silently return here.
     * Both transport callbacks must speak SSDP-like UDP only.
     */
    fun normalSearch(
        known: List<YeelightDevice>,
        quarantinedIps: Set<String>,
        multicast: () -> List<YeelightDevice>,
        directed: (List<YeelightDevice>) -> List<YeelightDevice>,
    ): NormalDiscovery {
        val announced = multicast()
        val targets = directedUdpTargets(known, announced, quarantinedIps)
        return NormalDiscovery(announced, if (targets.isEmpty()) emptyList() else directed(targets))
    }

    fun directedUdpTargets(
        known: List<YeelightDevice>,
        multicast: List<YeelightDevice>,
        quarantinedIps: Set<String>,
        limit: Int = 24,
    ): List<YeelightDevice> {
        require(limit in 1..24)
        val multicastIps = multicast.map { it.ip }.toSet()
        return known.asSequence()
            .filter { YeelightLan.isLocalIp(it.ip) }
            .filterNot { it.ip in multicastIps || it.ip in quarantinedIps }
            .distinctBy { it.ip }
            .take(limit)
            .toList()
    }

    /** A normal search must not create a single TCP control socket. */
    const val NORMAL_SEARCH_TCP_PROBES = 0
}
