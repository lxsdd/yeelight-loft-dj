package io.github.lxsdd.yeelightloftdj

import kotlin.math.max
import kotlin.math.roundToInt

enum class ChaseStyle { METEOR, RAINBOW_TAIL, COLOR_WAVE, KNIGHT_RIDER }
enum class ChaseDirection { FORWARD, REVERSE }

data class ChaseConfig(
    val style: ChaseStyle = ChaseStyle.RAINBOW_TAIL,
    val palette: List<Int> = listOf(0xFF3ABF, 0xFFB12C, 0x5A4BFF, 0x25D9E6, 0xFFE474),
    val stepMs: Int = 450,
    val tailLength: Int = 4,
    val brightness: Int = 75,
    val direction: ChaseDirection = ChaseDirection.FORWARD,
    val bounce: Boolean = false,
) {
    init {
        require(palette.size in 1..16 && palette.all { it in 0..0xFFFFFF })
        require(stepMs in 180..5000)
        require(tailLength in 1..24)
        require(brightness in 5..100)
    }
}

data class ChasePreset(val id: String, val name: String, val config: ChaseConfig) {
    init {
        require(id.isNotBlank() && id.length <= 80)
        require(name.isNotBlank() && name.length <= 64)
    }
}

/**
 * All movement is compiled into Yeelight native start_cf loops.
 * No stream of per-beat TCP commands or background worker is needed.
 * Each lamp gets just one start_cf, with a phase-specific flow.
 */
object ChaseCompiler {
    fun frames(config: ChaseConfig, lampIndex: Int, lampCount: Int): List<FlowFrame> {
        require(lampCount in 1..24) { "Lichtlauf unterstützt derzeit höchstens 24 Lampen" }
        require(lampIndex in 0 until lampCount)
        val forward = (0 until lampCount).toList()
        val path = if (config.direction == ChaseDirection.REVERSE) forward.reversed() else forward
        val positions = if (config.bounce && lampCount > 2) {
            path + path.drop(1).dropLast(1).reversed()
        } else path
        val cycle = if (positions.size < 2) listOf(0, 0) else positions
        val length = config.tailLength.coerceAtMost(lampCount)
        return cycle.mapIndexed { step, head ->
            // In bounce mode the tail follows the current travel direction,
            // not the initial one. Otherwise it would jump in front on return.
            val forwardNow = when {
                !config.bounce || lampCount < 3 -> config.direction == ChaseDirection.FORWARD
                step == 0 -> config.direction == ChaseDirection.FORWARD
                else -> head > cycle[step - 1]
            }
            val travel = if (forwardNow) {
                (head - lampIndex + lampCount) % lampCount
            } else {
                (lampIndex - head + lampCount) % lampCount
            }
            val palette = config.palette
            val rgb = when (config.style) {
                ChaseStyle.METEOR -> palette[step % palette.size]
                ChaseStyle.RAINBOW_TAIL -> palette[Math.floorMod(step - travel, palette.size)]
                ChaseStyle.COLOR_WAVE -> palette[Math.floorMod(step + lampIndex, palette.size)]
                ChaseStyle.KNIGHT_RIDER -> palette[step % palette.size]
            }
            val light = when (config.style) {
                ChaseStyle.COLOR_WAVE -> config.brightness
                ChaseStyle.KNIGHT_RIDER -> if (head == lampIndex) config.brightness else 1
                ChaseStyle.METEOR, ChaseStyle.RAINBOW_TAIL -> when {
                    travel == 0 -> config.brightness
                    travel >= length -> 1
                    else -> max(1, (config.brightness * (length - travel).toFloat() / length).roundToInt())
                }
            }
            FlowFrame(config.stepMs,
                if (config.style == ChaseStyle.KNIGHT_RIDER && head != lampIndex) 0 else rgb,
                light)
        }
    }

    fun expression(config: ChaseConfig, lampIndex: Int, lampCount: Int): String =
        frames(config, lampIndex, lampCount).joinToString(",") { it.encode() }

    fun decodePalette(input: String): List<Int> {
        val tokens = input.trim().split(Regex("[,;\\s]+")).filter { it.isNotEmpty() }
        require(tokens.size in 1..16) { "Bitte 1–16 Farben als #RRGGBB angeben" }
        return tokens.map { raw ->
            val hex = raw.removePrefix("#")
            require(Regex("[0-9a-fA-F]{6}").matches(hex)) { "Ungültige Farbe: $raw (Format #RRGGBB)" }
            hex.toInt(16)
        }
    }

    fun encodePalette(colors: List<Int>): String = colors.joinToString(", ") { "#%06X".format(it) }
}
