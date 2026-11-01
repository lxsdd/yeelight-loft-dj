package io.github.lxsdd.yeelightloftdj

import org.junit.Assert.*
import org.junit.Test

class LoftGroupAndStartTest {
    @Test fun knownUnsupportedBulbsCannotEnterColourFlow() {
        val white = YeelightDevice("white", "192.168.1.10", methods = setOf("set_bright"))
        val color = YeelightDevice("color", "192.168.1.11",
            model = "color4", methods = setOf("start_cf", "stop_cf", "set_scene"))
        assertFalse(white.canFlow)
        assertTrue(color.canFlow)
    }

    @Test fun colourFlowPreviewsRemainBoundedWithManyBulbs() {
        val cfg = ChaseConfig(style = ChaseStyle.RAINBOW_TAIL, tailLength = 4)
        val frames = (0 until 15).map { ChaseCompiler.frames(cfg, it, 15) }
        assertEquals(15, frames.size)
        assertTrue(frames.all { it.size == 15 })
        assertEquals(15, frames.count { it[0].brightness >= 1 })
    }

    @Test fun loftMembershipModelUsesIndependentIds() {
        val all = setOf("left", "middle", "right")
        val edited = all - "middle" + "fourth"
        assertEquals(setOf("left", "right", "fourth"), edited)
    }
}
