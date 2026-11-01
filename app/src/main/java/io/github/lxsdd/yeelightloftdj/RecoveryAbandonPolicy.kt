package io.github.lxsdd.yeelightloftdj

/**
 * Explicitly abandon an old app-side recovery journal entry for exactly one
 * local lamp. No claim about physical power cycling, no device I/O, no
 * assumption that the colour-flow is stopped or its state restored.
 */
object RecoveryAbandonPolicy {
    fun canDiscard(
        ip: String,
        pendingIps: Set<String>,
        acceptsPossibleRunningFlow: Boolean,
        acceptsLostRestore: Boolean,
        sessionBusy: Boolean,
    ): Boolean =
        YeelightLan.isLocalIp(ip) && ip in pendingIps &&
            acceptsPossibleRunningFlow && acceptsLostRestore && !sessionBusy
}
