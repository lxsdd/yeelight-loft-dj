package io.github.lxsdd.yeelightloftdj

import org.junit.Assert.*
import org.junit.Test

class ScenesTest {
    @Test fun fourScenes() {
        assertEquals(4, SceneLibrary.scenes.size)
        assertEquals(4, SceneLibrary.scenes.map { it.id }.toSet().size)
    }

    @Test fun validFlowFrames() {
        SceneLibrary.scenes.forEach { scene ->
            assertEquals(scene.frames.size * 4, scene.expression().split(",").size)
            assertTrue(scene.frames.all { it.durationMs >= 50 && it.brightness in 1..100 })
        }
    }

    @Test fun phaseOffsetWraps() {
        SceneLibrary.scenes.forEach { scene ->
            assertEquals(scene.expression(0), scene.expression(scene.frames.size))
            assertEquals(scene.expression(1), scene.expression(scene.frames.size + 1))
            assertNotEquals(scene.expression(0), scene.expression(1))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectInvalidBrightness() { FlowFrame(300, 0x123456, 101) }
}
