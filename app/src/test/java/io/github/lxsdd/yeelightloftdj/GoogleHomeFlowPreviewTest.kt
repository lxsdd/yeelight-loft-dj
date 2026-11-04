package io.github.lxsdd.yeelightloftdj

import org.junit.Assert.*
import org.junit.Test

class GoogleHomeFlowPreviewTest {
    private fun demo() = LoftScene("flow", "Nordlicht", "Test", emptyList(),
        listOf(FlowFrame(800, 0xAABBCC, 55), FlowFrame(900, 0x4400FF, 45),
            FlowFrame(1000, 0x00FF00, 42)))

    @Test fun requiresExplicitConsent() {
        assertThrows(IllegalArgumentException::class.java) {
            GoogleHomeFlowPreview.approximate(demo(), "Lichttest", listOf("Lampe - Zimmer"), false)
        }
    }

    @Test fun deliberatelySlowsAndKeepsConstantBrightness() {
        val result = GoogleHomeFlowPreview.approximate(demo(), "Lichttest",
            listOf("A - Wohnzimmer", "B - Wohnzimmer"), true)
        assertEquals(2, Regex("for: 10sec").findAll(result.yaml).count())
        assertEquals(6, Regex("type: device.command.ColorAbsolute").findAll(result.yaml).count())
        assertEquals(1, Regex("type: device.command.BrightnessAbsolute").findAll(result.yaml).count())
        assertTrue(result.yaml.contains("brightness: 55"))
        assertFalse(result.yaml.contains("brightness: 45"))
        assertTrue(result.warnings.any { it.contains("Originalhelligkeit") })
        assertTrue(result.warnings.any { it.contains("10 Sekunden") })
    }

    @Test fun acceptsUserDefinedExportTitleWithoutChangingPreset() {
        val original = demo()
        val export = GoogleHomeFlowPreview.approximate(original, "Nordlicht",
            listOf("A - Wohnzimmer"), true, sceneName = "Meine Google-Szene")
        assertTrue(export.yaml.contains("name: \"Meine Google-Szene\""))
        assertEquals("Nordlicht", original.name)
    }

    @Test fun rejectsExcessiveFlowFrames() {
        val original = demo()
        val long = original.copy(frames = (1..9).map { FlowFrame(750, 0xFFFFFF, 55) })
        assertThrows(IllegalArgumentException::class.java) {
            GoogleHomeFlowPreview.approximate(long, "Lichttest", listOf("A - Wohnzimmer"), true)
        }
    }

    @Test fun rejectsAmbiguousDuplicateTargets() {
        assertThrows(IllegalArgumentException::class.java) {
            GoogleHomeFlowPreview.approximate(demo(), "Lichttest",
                listOf("A - Wohnzimmer", "A - Wohnzimmer"), true)
        }
    }
}