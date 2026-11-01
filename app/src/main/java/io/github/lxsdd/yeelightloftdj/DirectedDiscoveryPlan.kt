package io.github.lxsdd.yeelightloftdj

/** Do not confuse failed multicast delivery with missing hardware features. */
object DirectedDiscoveryPlan {
    fun candidates(
        tcp: List<YeelightDevice>,
        multicast: List<YeelightDevice>,
    ): List<YeelightDevice> {
        val multicastIps = multicast.map { it.ip }.toSet()
        return tcp.filterNot { it.ip in multicastIps }
            .distinctBy { it.ip }
            .take(24)
    }

    fun missingMulticastCount(
        tcp: Collection<YeelightDevice>,
        multicast: Collection<YeelightDevice>,
    ): Int = tcp.map { it.ip }.toSet()
        .minus(multicast.map { it.ip }.toSet()).size
}
