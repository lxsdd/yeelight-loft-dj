package io.github.lxsdd.yeelightloftdj

import kotlin.math.ceil

/** One lamp on at a time. The previous lamp must be confirmed OFF before
 * issuing an ON command to its successor. Native CF dimming is NOT darkness.
 */
object KnightRiderPlan {
    fun path(count: Int, reverse: Boolean, bounce: Boolean): List<Int> {
        require(count in 2..24)
        val order = (0 until count).toList().let { if (reverse) it.reversed() else it }
        return if (bounce && count > 2)
            order + order.drop(1).dropLast(1).reversed()
        else order
    }

    /** 35 planned commands/minute per lamp leaves headroom under the shared
     * 45-command device gate and firmware's 60-per-connection quota. A bouncing interior lamp receives
     * at most 4 commands each 2*(count-1) steps; two lamps each receive 2 per
     * 2-step cycle. One readback per OFF adds another command. We conservatively
     * budget 3 commands per ON/OFF visit, including the shared TCP readback.
     */
    fun minimumStepMs(count: Int, bounce: Boolean): Int {
        require(count in 2..24)
        val cycle = path(count, reverse = false, bounce = bounce)
        val worstVisits = (0 until count).maxOf { index ->
            cycle.count { it == index }
        }
        // Each visit has an ON plus an OFF and a readback = 3 commands.
        val raw = ceil((60_000.0 * 3 * worstVisits) /
            (35.0 * cycle.size)).toInt()
        return raw.coerceIn(250, 4000)
    }

    fun effectiveStepMs(requested: Int, count: Int, bounce: Boolean): Int =
        maxOf(requested, minimumStepMs(count, bounce))

    fun stepColor(palette: List<Int>, step: Long): Int {
        require(palette.isNotEmpty() && palette.all { it in 0..0xFFFFFF })
        return palette[(step % palette.size).toInt()]
    }

    fun scannerCompatible(device: YeelightDevice): Boolean {
        val required = setOf("set_scene", "set_power", "get_prop", "set_rgb")
        // No unverified capability speculation when strict black/off is needed.
        return device.methods.containsAll(required)
    }
}
