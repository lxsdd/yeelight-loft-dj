package io.github.lxsdd.yeelightloftdj

import org.junit.Assert.*
import org.junit.Test

class MulticastComparisonTest {
    @Test fun fiveUdpButFifteenTcpAreNotTenUnsupportedBulbs() {
        val tcp = (1..15).map { "192.168.1.$it" }.toSet()
        val responders = (1..5).map { "192.168.1.$it" }.toSet()
        val result = MulticastComparison.compare(tcp, List(12) { responders })
        assertEquals(15, result.tcpCount)
        assertEquals(5, result.multicastCount)
        assertEquals(5, result.overlap)
        assertEquals(10, result.tcpOnly)
        assertEquals(12, MulticastComparison.responseRounds("192.168.1.1",
            List(12) { responders }))
        assertEquals(0, MulticastComparison.responseRounds("192.168.1.10",
            List(12) { responders }))
    }

    @Test fun intermittentResponsesIncreaseUniqueCoverage() {
        val tcp = (1..15).map { "192.168.1.$it" }.toSet()
        val rounds = listOf(
            (1..5).map { "192.168.1.$it" }.toSet(),
            (6..10).map { "192.168.1.$it" }.toSet(),
            (11..15).map { "192.168.1.$it" }.toSet())
        val result = MulticastComparison.compare(tcp, rounds)
        assertEquals(15, result.multicastCount)
        assertEquals(0, result.tcpOnly)
        assertEquals(listOf(5, 5, 5), result.roundCounts)
    }
}
