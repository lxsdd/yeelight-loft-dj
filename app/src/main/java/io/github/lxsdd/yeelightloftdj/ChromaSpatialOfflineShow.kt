package io.github.lxsdd.yeelightloftdj

import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Two-dimensional scene *research* coordinates (0..1000), not a physical room
 * or a discovered device location. Names/coordinates remain entirely local.
 */
data class ChromaSpatialLamp(val key: String, val x: Int, val y: Int) {
    init {
        require(key.isNotBlank() && key.length <= 64)
        require(x in 0..1000 && y in 0..1000)
    }
}

enum class ChromaSpatialPattern { RADIAL_WAVE, HORIZONTAL_SWEEP }

data class ChromaSpatialSettings(
    val pattern: ChromaSpatialPattern = ChromaSpatialPattern.RADIAL_WAVE,
    val palette: List<Int> = listOf(0xFF304F, 0x1BCBFA, 0xA056FF),
    val brightness: Int = 70,
    val stepMs: Int = 200,
    val distancePerStep: Int = 180,
    val bandWidth: Int = 240,
    val originX: Int = 500,
    val originY: Int = 500,
) {
    init {
        require(palette.size in 1..16 && palette.all { it in 0..0xFFFFFF })
        require(brightness in 1..100)
        require(stepMs in 100..2000)
        require(distancePerStep in 1..500)
        require(bandWidth in 50..1000)
        require(originX in 0..1000 && originY in 0..1000)
    }
}

data class ChromaSpatialColor(val rgb: Int, val brightness: Int) {
    init {
        require(rgb in 0..0xFFFFFF)
        require(brightness in 1..100)
    }
}

/**
 * Pure renderer with deterministic, bounded operations.
 *
 * Intentional limitation: brightness=1 is NOT electrically OFF. The user
 * requirement "only currently active lamps emit light" (true Knight Rider)
 * MUST NOT be advertised by this UDP color-only preview. That requires a
 * verified OFF command plus readback and a separate hardware-approved runner.
 */
object ChromaSpatialRenderer {
    fun render(
        lamps: List<ChromaSpatialLamp>,
        settings: ChromaSpatialSettings,
        step: Long,
    ): Map<String, ChromaSpatialColor> {
        require(lamps.size in 1..ChromaUdpOfflineFleet.MAX_LAMPS)
        require(lamps.map { it.key }.distinct().size == lamps.size)
        require(step in 0..10_000L)
        val maxDistance = if (settings.pattern == ChromaSpatialPattern.RADIAL_WAVE) 1415 else 1000
        val loop = maxDistance + settings.bandWidth
        val head = (step * settings.distancePerStep % loop).toInt()
        return lamps.associate { lamp ->
            val distance = when (settings.pattern) {
                ChromaSpatialPattern.HORIZONTAL_SWEEP -> lamp.x
                ChromaSpatialPattern.RADIAL_WAVE ->
                    hypot((lamp.x - settings.originX).toDouble(),
                        (lamp.y - settings.originY).toDouble()).roundToInt()
            }
            // A closed traversal, with a wrap-around taper and no negative phase.
            val raw = kotlin.math.abs(distance - head)
            val separation = min(raw, loop - raw)
            val fraction = (1.0 - separation.toDouble() / settings.bandWidth)
                .coerceIn(0.0, 1.0)
            val level = (settings.brightness * fraction).roundToInt().coerceIn(1, 100)
            val colorIndex = Math.floorMod(step + distance / settings.bandWidth,
                settings.palette.size.toLong()).toInt()
            lamp.key to ChromaSpatialColor(settings.palette[colorIndex], level)
        }
    }
}

enum class ChromaSpatialShowState { NEW, ACTIVE, STOPPED }

/**
 * Offline group preflight and bounded effect clock. This orchestrates the
 * existing in-memory tokenized fleet and latest-value coalescer only.
 *
 * The preflight is NOT an atomic network transaction: no real sockets exist.
 * An unexpected terminal lane cancels all other lanes at the next event, and
 * no automatic reconnect, restoration or classic TCP fallback is attempted.
 */
class ChromaSpatialOfflineShow(
    private val lamps: List<ChromaSpatialLamp>,
    private val settings: ChromaSpatialSettings,
    private val fleet: ChromaUdpOfflineFleet,
    private val coalescer: ChromaOfflineLatestValue,
) {
    init {
        require(lamps.size in 2..ChromaUdpOfflineFleet.MAX_LAMPS)
        require(lamps.map { it.key }.distinct().size == lamps.size)
        require(lamps.all { fleet.snapshot(it.key) != null })
    }

    data class SimulatedFrame(val key: String, val frame: ChromaPreviewFrame)

    var state: ChromaSpatialShowState = ChromaSpatialShowState.NEW
        private set
    private var startedAt: Long = -1
    private var lastObservedAt: Long = -1
    private var lastFrameStep: Long = -1

    private fun ready(): Boolean = lamps.all { lamp ->
        fleet.snapshot(lamp.key)?.let {
            !it.safetyStopped && it.simulatedTokenHeld
        } == true
    }

    /** No session negotiation. Sessions must have been activated by a fake. */
    fun arm(nowMs: Long): Boolean {
        if (state != ChromaSpatialShowState.NEW || nowMs < 0) return false
        if (!ready()) {
            stop()
            return false
        }
        startedAt = nowMs
        lastObservedAt = nowMs
        state = ChromaSpatialShowState.ACTIVE
        return true
    }

    /**
     * Every call is explicitly clocked by a test. No timer / thread / network.
     * A maximum of one preview frame per lamp per call; never a backlog replay.
     */
    fun tick(nowMs: Long): List<SimulatedFrame> {
        if (state != ChromaSpatialShowState.ACTIVE) return emptyList()
        if (nowMs < lastObservedAt || nowMs - startedAt >=
            ChromaUdpOfflineFleet.MAX_LIFETIME_MS || !ready()) {
            stop()
            return emptyList()
        }
        lastObservedAt = nowMs
        val step = (nowMs - startedAt) / settings.stepMs
        if (step != lastFrameStep) {
            val colors = ChromaSpatialRenderer.render(lamps, settings, step)
            // Stage all desired values before emitting any. If one fails to
            // stage, close the entire group (not partial speculative output).
            for (lamp in lamps) {
                val target = colors.getValue(lamp.key)
                if (!coalescer.offer(lamp.key, target.rgb, target.brightness, nowMs)) {
                    stop()
                    return emptyList()
                }
            }
            lastFrameStep = step
        }
        val results = mutableListOf<SimulatedFrame>()
        lamps.forEach { lamp ->
            coalescer.poll(lamp.key, nowMs)?.let { results.add(SimulatedFrame(lamp.key, it)) }
        }
        // The strict 45-frame ceiling can stop a lane during this tick.
        // Finish the simulated group immediately, without a later retry.
        if (!ready()) stop()
        return results
    }

    /** Terminal. Token erasure only, no physical "off" / restore command. */
    fun stop() {
        if (state == ChromaSpatialShowState.STOPPED) return
        state = ChromaSpatialShowState.STOPPED
        lamps.forEach { coalescer.stop(it.key) }
    }

    fun allQuarantinedOrStopped(): Boolean =
        state == ChromaSpatialShowState.STOPPED &&
        lamps.all { fleet.snapshot(it.key)?.safetyStopped == true }
}
