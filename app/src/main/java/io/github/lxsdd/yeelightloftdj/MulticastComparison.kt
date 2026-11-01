package io.github.lxsdd.yeelightloftdj

/** Pure read-only diagnostic: missing UDP is not evidence of missing TCP control. */
object MulticastComparison {
    data class Result(
        val tcpCount: Int,
        val multicastCount: Int,
        val overlap: Int,
        val tcpOnly: Int,
        val roundCounts: List<Int>,
    )
    fun compare(tcp: Set<String>, rounds: List<Set<String>>): Result {
        val udp = rounds.flatten().toSet()
        return Result(
            tcpCount = tcp.size,
            multicastCount = udp.size,
            overlap = (tcp intersect udp).size,
            tcpOnly = (tcp - udp).size,
            roundCounts = rounds.map { it.size },
        )
    }
    fun responseRounds(ip: String, rounds: List<Set<String>>): Int =
        rounds.count { ip in it }
}
