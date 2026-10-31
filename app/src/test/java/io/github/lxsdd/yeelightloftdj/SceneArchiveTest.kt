package io.github.lxsdd.yeelightloftdj

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

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
            val wrong = JSONObject(valid).put("version", 999).toString()
            SceneArchive.decode(wrong)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SceneArchive.decode(" ".repeat(SceneArchive.maxBytes + 1))
        }
    }

    @Test fun rejectsOutOfBoundsChaseAndFlowFrames() {
        val json = SceneArchive.encode(SceneArchiveData(SceneLibrary.scenes.take(1), listOf(chase)))
        val invalidTail = JSONObject(json)
        invalidTail.getJSONArray("chases").getJSONObject(0).put("tail", 77)
        assertThrows(IllegalArgumentException::class.java) {
            SceneArchive.decode(invalidTail.toString())
        }
        val invalidBrightness = JSONObject(json)
        invalidBrightness.getJSONArray("flows").getJSONObject(0)
            .getJSONArray("frames").getJSONObject(0).put("brightness", 101)
        assertThrows(IllegalArgumentException::class.java) {
            SceneArchive.decode(invalidBrightness.toString())
        }
    }

    @Test fun singleSceneFilesRoundTripIndependently() {
        val oneFlow = SceneArchive.decode(
            SceneArchive.encode(SceneArchiveData(listOf(SceneLibrary.scenes[0]), emptyList()))
        )
        assertEquals(1, oneFlow.flows.size)
        assertEquals(0, oneFlow.chases.size)
        assertEquals(SceneLibrary.scenes[0].frames, oneFlow.flows[0].frames)

        val oneChase = SceneArchive.decode(
            SceneArchive.encode(SceneArchiveData(emptyList(), listOf(chase)))
        )
        assertEquals(0, oneChase.flows.size)
        assertEquals(listOf(chase), oneChase.chases)
    }

    @Test fun emptyArchiveRoundTrips() {
        val data = SceneArchive.decode(SceneArchive.encode(SceneArchiveData(emptyList(), emptyList())))
        assertEquals(0, data.flows.size)
        assertEquals(0, data.chases.size)
    }
}
