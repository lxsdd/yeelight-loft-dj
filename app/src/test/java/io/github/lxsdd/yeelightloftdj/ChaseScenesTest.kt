package io.github.lxsdd.yeelightloftdj

import org.junit.Assert.*
import org.junit.Test

class ChaseScenesTest {
    @Test fun everyBulbGetsExactlyOneMovingHead() {
        val conf = ChaseConfig(style = ChaseStyle.METEOR, palette = listOf(0xFF0000),
            stepMs = 400, tailLength = 1, brightness = 80)
        val all = (0 until 15).map { ChaseCompiler.frames(conf, it, 15) }
        assertTrue(all.all { it.size == 15 })
        for (step in 0 until 15) {
            assertEquals(1, all.count { frames -> frames[step].brightness == 80 })
            assertEquals(step, all.indexOfFirst { frames -> frames[step].brightness == 80 })
        }
    }

    @Test fun reverseRunsBackwards() {
        val conf = ChaseConfig(style = ChaseStyle.METEOR, palette = listOf(0x00FF00),
            tailLength = 1, direction = ChaseDirection.REVERSE)
        val all = (0 until 4).map { ChaseCompiler.frames(conf, it, 4) }
        val order = (0 until 4).map { step -> all.indexOfFirst { it[step].brightness == conf.brightness } }
        assertEquals(listOf(3, 2, 1, 0), order)
    }

    @Test fun bouncingDoesNotDoubleEndpoints() {
        val conf = ChaseConfig(bounce = true, tailLength = 1)
        val all = (0 until 4).map { ChaseCompiler.frames(conf, it, 4) }
        assertEquals(6, all[0].size)
        val order = (0 until 6).map { step -> all.indexOfFirst { it[step].brightness == conf.brightness } }
        assertEquals(listOf(0, 1, 2, 3, 2, 1), order)
    }

    @Test fun rainbowTrailDimsAwayFromHead() {
        val conf = ChaseConfig(tailLength = 4, brightness = 80)
        val all = (0 until 8).map { ChaseCompiler.frames(conf, it, 8) }
        val intensities = listOf(0, 7, 6, 5, 4).map { all[it][0].brightness }
        assertEquals(listOf(80, 60, 40, 20, 1), intensities)
        assertNotEquals(all[0][0].rgb, all[7][0].rgb)
    }

    @Test fun colorWaveIlluminatesAllLamps() {
        val conf = ChaseConfig(style = ChaseStyle.COLOR_WAVE)
        val all = (0 until 15).map { ChaseCompiler.frames(conf, it, 15) }
        assertTrue(all.all { frames -> frames.all { it.brightness == conf.brightness } })
    }

    @Test fun expressionIsValidYeelightFlowString() {
        val expression = ChaseCompiler.expression(ChaseConfig(), 1, 15)
        assertEquals(15 * 4, expression.split(",").size)
        assertEquals(450, expression.split(",")[0].toInt())
        assertEquals("1", expression.split(",")[1])
    }

    @Test fun oneLampStillProducesAtLeastTwoFrames() {
        assertEquals(2, ChaseCompiler.frames(ChaseConfig(), 0, 1).size)
    }

    @Test fun paletteAcceptsHexOnlyAndBoundedValues() {
        assertEquals(listOf(0xFF0099, 0x25BEA0), ChaseCompiler.decodePalette("#FF0099; #25bea0"))
        assertThrows(IllegalArgumentException::class.java) { ChaseCompiler.decodePalette("#12345") }
        assertThrows(IllegalArgumentException::class.java) { ChaseCompiler.decodePalette("javascript:alert(1)") }
        assertThrows(IllegalArgumentException::class.java) { ChaseConfig(stepMs = 30) }
        assertThrows(IllegalArgumentException::class.java) { ChaseCompiler.frames(ChaseConfig(), 0, 25) }
    }
}
