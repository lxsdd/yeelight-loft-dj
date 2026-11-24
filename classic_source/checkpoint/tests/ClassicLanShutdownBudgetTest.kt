package loftdj.classic

/** Exercises the actual production LAN budget without sockets or private devices. */
fun main() {
    for (size in intArrayOf(1, 14, 32)) {
        val quota = ClassicLanRpcBudget()
        for (i in 0 until size) {
            val id = "bulb$i"
            val owner = Any()
            check(quota.admit(id, 100, ClassicLanRpcBudget.Phase.READ))
            check(quota.admit(id, 101, ClassicLanRpcBudget.Phase.START, owner))
            check(!quota.admit(id, 102, ClassicLanRpcBudget.Phase.READ))
            check(!quota.admit(id, 102, ClassicLanRpcBudget.Phase.STOP, Any()))
            check(!quota.admit(id, 102, ClassicLanRpcBudget.Phase.VERIFY_STOP, owner))
            check(quota.admit(id, 102, ClassicLanRpcBudget.Phase.STOP, owner))
            check(!quota.admit(id, 103, ClassicLanRpcBudget.Phase.STOP, owner))
            check(quota.admit(id, 103, ClassicLanRpcBudget.Phase.VERIFY_STOP, owner))
            check(!quota.admit(id, 104, ClassicLanRpcBudget.Phase.VERIFY_STOP, owner))
            check(quota.admit(id, 104, ClassicLanRpcBudget.Phase.READ))
            check(!quota.retire(id, owner))
        }
    }
    run {
        val quota = ClassicLanRpcBudget()
        val owner = Any()
        repeat(4) { check(quota.admit("busy", it.toLong())) }
        check(quota.admit("busy", 5, ClassicLanRpcBudget.Phase.READ))
        check(quota.admit("busy", 6, ClassicLanRpcBudget.Phase.START, owner))
        check(!quota.admit("busy", 7, ClassicLanRpcBudget.Phase.READ))
        check(quota.admit("busy", 8, ClassicLanRpcBudget.Phase.STOP, owner))
        check(quota.admit("busy", 9, ClassicLanRpcBudget.Phase.VERIFY_STOP, owner))
        check(!quota.admit("busy", 10))
        check(quota.admit("other", 10))
        check(quota.admit("busy", 60_004))
    }
    run {
        val quota = ClassicLanRpcBudget()
        repeat(5) { check(quota.admit("near-full", it.toLong())) }
        check(quota.admit("near-full", 5, ClassicLanRpcBudget.Phase.READ))
        check(!quota.admit("near-full", 6, ClassicLanRpcBudget.Phase.START, Any()))
        check(!quota.admit("near-full", 6, ClassicLanRpcBudget.Phase.STOP, Any()))
        check(quota.admit("near-full", 6, ClassicLanRpcBudget.Phase.READ))
        check(quota.admit("near-full", 7, ClassicLanRpcBudget.Phase.READ))
        check(!quota.admit("near-full", 8))
        check(quota.admit("near-full", 60_007))
    }
    run {
        val quota = ClassicLanRpcBudget()
        val owner = Any()
        check(quota.admit("abort", 0, ClassicLanRpcBudget.Phase.START, owner))
        check(!quota.retire("abort", Any()))
        check(!quota.admit("abort", 1, ClassicLanRpcBudget.Phase.READ))
        check(quota.retire("abort", owner))
        check(!quota.admit("abort", 2, ClassicLanRpcBudget.Phase.STOP, owner))
        check(quota.admit("abort", 2, ClassicLanRpcBudget.Phase.READ))
        check(!quota.admit("abort", 1, ClassicLanRpcBudget.Phase.READ))
    }
    run {
        val quota = ClassicLanRpcBudget()
        val owner = Any()
        check(quota.admit("wrap", Long.MIN_VALUE + 20, ClassicLanRpcBudget.Phase.START, owner))
        check(quota.admit("wrap", Long.MIN_VALUE + 30, ClassicLanRpcBudget.Phase.STOP, owner))
        check(quota.admit("wrap", Long.MIN_VALUE + 40, ClassicLanRpcBudget.Phase.VERIFY_STOP, owner))
        check(!quota.admit("wrap", Long.MIN_VALUE + 39))
        check(quota.admit("wrap", Long.MAX_VALUE - 2))
        check(!quota.admit("wrap", Long.MIN_VALUE))
    }
    println("CLASSIC_SHUTDOWN_BUDGET_PASS")
}
