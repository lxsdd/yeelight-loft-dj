package io.github.lxsdd.yeelightloftdj

/**
 * A physical power cycle terminates a bulb's volatile colour-flow state.
 * This gate only allows explicitly abandoning a *local* recovery marker;
 * it neither proves state restoration nor sends a command to the device.
 */
object PowerCycleRecoveryPolicy {
    fun canRelease(
        ip: String,
        pendingIps: Set<String>,
        physicallyPowerCycled: Boolean,
        acceptsLostRestore: Boolean,
        sessionBusy: Boolean,
    ): Boolean =
        YeelightLan.isLocalIp(ip) && ip in pendingIps &&
            physicallyPowerCycled && acceptsLostRestore && !sessionBusy
}
