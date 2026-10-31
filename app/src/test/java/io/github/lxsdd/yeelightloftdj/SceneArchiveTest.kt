package io.github.lxsdd.yeelightloftdj

import org.junit.Assert.*
import org.junit.Test

class SceneArchiveTest {
    private val chase = ChasePreset("meteor-saved", "Sternschnuppe",
        ChaseConfig(style = ChaseStyle.METEOR, stepMs = 600,
            palette = listOf(0x0088DD, 0xFF4FAA), bounce = true,
            direction = ChaseDirection.REVERSE, tailLength = 3, brightness = 87))

    @Test fun exportsBuiltInColorFlowsAndCustomChases() {
        val original = SceneArchiveData(SceneLibrary.scenes, listOf(chase))
        val json = SceneArchive.encode(original)
        assertFalse(json.contains("192.168."))
        assertFalse(json.contains("get_prop"))
        val imported = SceneArchive.decode(json)
        assertEquals(4, imported.flows.size)
        assertEquals(SceneLibrary.scenes.first().frames, imported.flows.first().frames)
        assertEquals(listOf(chase), imported.chases)
    }

    @Test fun rejectsWrongFileAndUnknownVersions() {
        assertThrows(IllegalArgumentException::class.java) { SceneArchive.decode("{}") }
        val valid = SceneArchive.encode(SceneArchiveData(emptyList(), emptyList()))
        assertThrows(IllegalArgumentException::class.java) {
            SceneArchive.decode(valid.replace("\"version\": 1", "\"version\": 999"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SceneArchive.decode(" ".repeat(SceneArchive.maxBytes + 1))
        }
    }

    @Test fun rejectsOutOfBoundsChaseAndFlowFrames() {
        val json = SceneArchive.encode(SceneArchiveData(SceneLibrary.scenes.take(1), listOf(chase)))
        assertThrows(IllegalArgumentException::class.java) {
            SceneArchive.decode(json.replace("\"tail\": 3", "\"tail\": 77"))
        }
        val mutated = json.replace("\"brightness\": 55", "\"brightness\": 101")
        // Depending on builtin frame brightness, the replacement is detectable.
        if (mutated != json) {
            assertThrows(IllegalArgumentException::class.java) { SceneArchive.decode(mutated) }
        }
    }

    @Test fun emptyArchiveRoundTrips() {
        val data = SceneArchive.decode(SceneArchive.encode(SceneArchiveData(emptyList(), emptyList())))
        assertEquals(0, data.flows.size)
        assertEquals(0, data.chases.size)
    }
}
