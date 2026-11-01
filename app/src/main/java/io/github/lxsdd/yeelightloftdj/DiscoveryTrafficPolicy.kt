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
