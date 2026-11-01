package io.github.lxsdd.yeelightloftdj

/**
 * Hardware operations are injected so exact off-before-on behaviour is
 * deterministic and regression-testable without real bulbs.
 * Results do NOT fabricate physical confirmation from a command ACK.
 */
class KnightRiderRunner {
    data class Result(val unresolved: Set<String>, val steps: Long, val error: String?)

    suspend fun execute(
        devices: List<YeelightDevice>,
        config: ChaseConfig,
        isStopping: () -> Boolean,
        powerOff: suspend (YeelightDevice) -> Boolean,
        lightOn: suspend (YeelightDevice, Int, Int) -> Unit,
        waitStep: suspend (Long) -> Unit,
        onStep: suspend (Int, Long) -> Unit,
    ): Result {
        require(devices.size in 2..24)
        require(config.style == ChaseStyle.KNIGHT_RIDER)
        val unsafe = devices.map { it.ip }.toMutableSet()
        var active: YeelightDevice? = null
        var steps = 0L
        var error: String? = null
        try {
            // Only perform the show after every selected participant is
            // actually VERIFIED off. Never light next bulb on an unknown ACK.
            for (device in devices) {
                if (!powerOff(device)) {
                    error = "Start nicht sicher: " + device.ip + " ist nicht als AUS bestätigt"
                    return Result(unsafe, steps, error)
                }
                unsafe.remove(device.ip)
            }
            if (isStopping()) return Result(unsafe,steps,null)
            val path = KnightRiderPlan.path(
                devices.size, config.direction == ChaseDirection.REVERSE, config.bounce
            )
            val dwell = KnightRiderPlan.effectiveStepMs(
                config.stepMs, devices.size, config.bounce
            ).toLong()
            while (!isStopping()) {
                val next = devices[path[(steps % path.size).toInt()]]
                if (active != null) {
                    if (!powerOff(active)) {
                        error = "Scanner gestoppt: vorherige Lampe konnte nicht als AUS bestätigt werden"
                        break
                    }
                    unsafe.remove(active.ip)
                    active = null
                }
                if (isStopping()) break
                // Record a possibly active lamp before sending its ON command:
                // even timeout/unknown ACK must remain in recovery.
                unsafe.add(next.ip)
                lightOn(next, KnightRiderPlan.stepColor(config.palette,steps),
                    config.brightness)
                active = next
                steps++
                onStep(path[((steps-1) % path.size).toInt()], steps)
                waitStep(dwell)
            }
        } catch (e: Exception) {
            error = "Scanner abgebrochen: " + (e.message ?: e.javaClass.simpleName)
        } finally {
            // Every bulb that might have been touched is addressed, including
            // a newly ON bulb and preflight failures. A failed verification
            // remains in the persisted recovery journal for later handling.
            devices.forEach { device ->
                val off = runCatching { powerOff(device) }.getOrDefault(false)
                if (off) unsafe.remove(device.ip) else unsafe.add(device.ip)
            }
        }
        return Result(unsafe,steps,error)
    }
}
