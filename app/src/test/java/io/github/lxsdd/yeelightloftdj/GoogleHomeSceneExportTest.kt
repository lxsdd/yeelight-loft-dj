package io.github.lxsdd.yeelightloftdj

import org.junit.Assert.*
import org.junit.Test

class GoogleHomeSceneExportTest {
    private fun rgb(name: String = "Couchtisch - Wohnzimmer", rgb: Int = 0xFF7700, brightness: Int = 50) =
        HomeLamp(name, true, brightness, HomeColor.Rgb(rgb))
    private fun frames() = listOf(
        HomeSceneStep(10_000L, listOf(rgb())),
        HomeSceneStep(10_000L, listOf(rgb(rgb = 0x0022AA))),
        HomeSceneStep(10_000L, listOf(rgb(rgb = 0x00FF00))),
    )

    @Test fun staticIncludesRgbBrightnessOffAndTemperature() {
        val result = GoogleHomeSceneExport.staticScene("Abend", "Loft Abend",
            listOf(rgb(), HomeLamp("Decke - Esszimmer", true, 42, HomeColor.Temperature(3100)),
                HomeLamp("Flur - Flur", false)))
        val yaml = result.yaml
        assertTrue(yaml.contains("spectrumRGB: \"FF7700\""))
        assertTrue(yaml.contains("temperature: 3100K"))
        assertTrue(yaml.contains("brightness: 42"))
        assertTrue(yaml.contains("brightness: 50"))
        assertTrue(yaml.contains("on: false"))
        assertTrue(yaml.contains("eventData: query\n      is: \"Loft Abend\""))
        assertTrue(yaml.contains("for: 2sec"))
        assertFalse(yaml.contains("192.168."))
        assertEquals(6, result.actionCount) // off, two colors, stage delay, two brightness groups
    }

    @Test fun sameBrightnessIsGroupedToReduceCommandCount() {
        val r = GoogleHomeSceneExport.staticScene("Abend", "Szene Abend",
            listOf(rgb(), rgb("Flur - Flur", 0xFFFFFF)))
        assertEquals(4, r.actionCount) // two colors, one stage delay, one brightness group
        assertEquals(1, Regex("type: device.command.BrightnessAbsolute").findAll(r.yaml).count())
    }

    @Test fun slowStoryboardIsFiniteAndNeverLoops() {
        val result = GoogleHomeSceneExport.slowStoryboard("Nordlicht", "Nordlicht starten", frames())
        assertEquals(2, Regex("for: 10sec").findAll(result.yaml).count())
        assertEquals(3, Regex("type: device.command.ColorAbsolute").findAll(result.yaml).count())
        assertFalse(result.yaml.contains("repeat"))
        assertFalse(result.yaml.contains("while"))
    }

    @Test fun rejectsFastAnimationInsteadOfSilentlySlowingIt() {
        assertThrows(IllegalArgumentException::class.java) {
            GoogleHomeSceneExport.slowStoryboard("Lauflicht", "Lauflicht starten",
                listOf(HomeSceneStep(300L, listOf(rgb())), frames()[1]))
        }
        assertThrows(IllegalArgumentException::class.java) {
            GoogleHomeSceneExport.slowStoryboard("Lauflicht", "Lauflicht starten",
                listOf(HomeSceneStep(10_250L, listOf(rgb())), frames()[1]))
        }
    }

    @Test fun rejectsStoryboardWithVariableBrightnessOrSwitching() {
        assertThrows(IllegalArgumentException::class.java) {
            GoogleHomeSceneExport.slowStoryboard("x", "Lichtwechsel",
                listOf(frames()[0], HomeSceneStep(10_000L, listOf(rgb(brightness = 65)))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            GoogleHomeSceneExport.slowStoryboard("x", "Lichtwechsel",
                listOf(frames()[0], HomeSceneStep(10_000L, listOf(HomeLamp("Couchtisch - Wohnzimmer", false)))))
        }
    }

    @Test fun rejectsUnsafeOrAmbiguousDeviceMappings() {
        assertThrows(IllegalArgumentException::class.java) {
            GoogleHomeSceneExport.staticScene("x", "Lichtwechsel", listOf(rgb("Einzelname")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            GoogleHomeSceneExport.staticScene("x", "Lichtwechsel", listOf(rgb(), rgb()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            GoogleHomeSceneExport.staticScene("x", "Lichtwechsel", listOf(rgb("192.168.1.2 - Zimmer")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            GoogleHomeSceneExport.staticScene("x", "Lichtwechsel", emptyList())
        }
    }

    @Test fun yamlInjectionIsEscapedAndControlsRejected() {
        val yaml = GoogleHomeSceneExport.staticScene("Abend: \"Test\"", "Party: \"Go\"",
            listOf(rgb("Tisch: \"LED\" - Wohnzimmer"))).yaml
        assertTrue(yaml.contains("name: \"Abend: \\\"Test\\\"\""))
        assertTrue(yaml.contains("devices: \"Tisch: \\\"LED\\\" - Wohnzimmer\""))
        assertThrows(IllegalArgumentException::class.java) {
            GoogleHomeSceneExport.staticScene("x\nactions: dangerous", "Lichtwechsel", listOf(rgb()))
        }
    }

    @Test fun rejectsMissingColorAndInvalidRange() {
        assertThrows(IllegalArgumentException::class.java) {
            GoogleHomeSceneExport.staticScene("x", "Lichtwechsel", listOf(HomeLamp("X - Room", true)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            GoogleHomeSceneExport.staticScene("x", "Lichtwechsel", listOf(rgb(brightness = 0)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            GoogleHomeSceneExport.staticScene("x", "Lichtwechsel", listOf(rgb(rgb = -1)))
        }
    }

    @Test fun rejectsOversizeAndFrameIdentityMismatch() {
        assertThrows(IllegalArgumentException::class.java) {
            GoogleHomeSceneExport.staticScene("x", "Lichtwechsel", (1..17).map { rgb("L$it - Room") })
        }
        assertThrows(IllegalArgumentException::class.java) {
            GoogleHomeSceneExport.slowStoryboard("x", "Lichtwechsel", listOf(
                frames()[0], HomeSceneStep(10_000L, listOf(rgb("Andere - Room")))))
        }
    }

    @Test fun adapterHandlesRgbKelvinHsvAndOffWithoutExportingIp() {
        val mapping = linkedMapOf(
            "192.168.178.135" to "A - Room", "192.168.178.136" to "B - Room",
            "192.168.178.137" to "C - Room", "192.168.178.138" to "D - Room")
        val data = linkedMapOf(
            "192.168.178.135" to LampSnapshot(true, 44, 1, 0xF0A024, null, null, null),
            "192.168.178.136" to LampSnapshot(true, 67, 2, null, 2700, null, null),
            "192.168.178.137" to LampSnapshot(true, 70, 3, null, null, 120, 100),
            "192.168.178.138" to LampSnapshot(false, 50, 0, null, null, null, null))
        val lights = GoogleHomeSnapshotAdapter.fromFreshSnapshots(mapping, data)
        assertEquals(HomeColor.Rgb(0x00FF00), lights[2].color)
        assertEquals(null, lights[3].color)
        val yaml = GoogleHomeSceneExport.staticScene("Test", "Lichter Test", lights).yaml
        assertFalse(yaml.contains("192.168"))
        assertTrue(yaml.contains("spectrumRGB: \"00FF00\""))
    }

    @Test fun adapterFailsClosedOnMissingOrIncompleteSnapshots() {
        assertThrows(IllegalArgumentException::class.java) {
            GoogleHomeSnapshotAdapter.fromFreshSnapshots(
                mapOf("192.168.1.3" to "A - Room"), emptyMap())
        }
        assertThrows(IllegalArgumentException::class.java) {
            GoogleHomeSnapshotAdapter.fromFreshSnapshots(
                mapOf("192.168.1.3" to "A - Room"),
                mapOf("192.168.1.3" to LampSnapshot(true, 50, 1, null, null, null, null)))
        }
    }
    @Test fun fourteenLightsWithIndependentColorsStayWithinBound() {
        val lamps = (1..14).map { number ->
            HomeLamp("Lampe $number - Esszimmer", true, number * 7,
                HomeColor.Rgb((number * 0x110311) and 0xFFFFFF))
        }
        val result = GoogleHomeSceneExport.staticScene("Vierzehn", "Vierzehn Lichter", lamps)
        assertTrue(result.actionCount <= GoogleHomeSceneExport.MAX_ACTIONS)
        assertEquals(14, Regex("type: device.command.ColorAbsolute").findAll(result.yaml).count())
        assertEquals(14, Regex("type: device.command.BrightnessAbsolute").findAll(result.yaml).count())
    }

    @Test fun rgbHexKeepsSixCharactersAndLeadingZeroes() {
        val yaml = GoogleHomeSceneExport.staticScene("Schwarz", "Szene Schwarz",
            listOf(rgb(rgb = 0x000005))).yaml
        assertTrue(yaml.contains("spectrumRGB: \"000005\""))
        assertFalse(yaml.contains("spectrumRGB: 5\n"))
    }

    @Test fun caseInsensitiveDuplicateMappingsMustFailClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            GoogleHomeSceneExport.staticScene("Abend", "Abendlicht starten", listOf(
                rgb("Lampe - Wohnzimmer"), rgb("lampe - WOHNZIMMER")))
        }
    }

    @Test fun mappingsCannotContainEmptyRoomOrInvisibleOuterWhitespace() {
        for (bad in listOf(" - Raum", "Lampe - ", " Lampe - Raum", "Lampe - Raum ")) {
            assertThrows(IllegalArgumentException::class.java) {
                GoogleHomeSceneExport.staticScene("Abend", "Abendlicht starten", listOf(rgb(bad)))
            }
        }
    }

    @Test fun allOffDoesNotEmitAccidentalPowerOnColorOrDelay() {
        val yaml = GoogleHomeSceneExport.staticScene("Aus", "Licht aus",
            listOf(HomeLamp("A - Wohnzimmer", false), HomeLamp("B - Flur", false))).yaml
        assertEquals(2, Regex("type: device.command.OnOff").findAll(yaml).count())
        assertFalse(yaml.contains("device.command.ColorAbsolute"))
        assertFalse(yaml.contains("time.delay"))
    }

    @Test fun malformedNamesCannotInjectYamlKeys() {
        assertThrows(IllegalArgumentException::class.java) {
            GoogleHomeSceneExport.staticScene("Szenen", "Licht an",
                listOf(rgb("Lampe - Wohnzimmer\n    - type: device.command.OnOff")))
        }
        val yaml = GoogleHomeSceneExport.staticScene("A\\B", "Szene \"Hallo\"",
            listOf(rgb("Tisch\\Special - Esszimmer"))).yaml
        assertTrue(yaml.contains("Tisch\\\\Special"))
    }

    @Test fun comparisonGeneratesTwoIndependentVoiceStartersForOneRgbLamp() {
        val original = rgb(rgb = 0xFFB36B, brightness = 45)
        val result = GoogleHomeSceneExport.rgbComparison(
            "Farbvergleich", "Loft Farbe A", "Loft Farbe B", original, "#FF7700"
        )
        assertEquals(2, Regex("type: assistant.event.OkGoogle").findAll(result.yaml).count())
        assertEquals(2, Regex("type: device.command.ColorAbsolute").findAll(result.yaml).count())
        assertEquals(2, Regex("type: device.command.BrightnessAbsolute").findAll(result.yaml).count())
        assertEquals(2, Regex("for: 2sec").findAll(result.yaml).count())
        assertEquals(6, result.actionCount)
        assertTrue(result.yaml.contains("spectrumRGB: \"FFB36B\""))
        assertTrue(result.yaml.contains("spectrumRGB: \"FF7700\""))
        assertTrue(result.yaml.contains("is: \"Loft Farbe A\""))
        assertTrue(result.yaml.contains("is: \"Loft Farbe B\""))
        assertEquals(2, Regex("devices: \"Couchtisch - Wohnzimmer\"").findAll(result.yaml).count())
        assertEquals(HomeColor.Rgb(0xFFB36B), original.color)
        assertFalse(result.yaml.contains("192.168."))
        assertFalse(result.yaml.contains("repeat:"))
    }

    @Test fun comparisonRejectsIdenticalColorsAndMissingCorrectedHex() {
        assertThrows(IllegalArgumentException::class.java) {
            GoogleHomeSceneExport.rgbComparison("A/B", "Loft A", "Loft B", rgb(), "FF7700")
        }
        assertThrows(IllegalArgumentException::class.java) {
            GoogleHomeSceneExport.rgbComparison("A/B", "Loft A", "Loft B", rgb(), "0x00FF00")
        }
    }

    @Test fun comparisonRejectsDuplicateAndUnsafeVoicePhrases() {
        assertThrows(IllegalArgumentException::class.java) {
            GoogleHomeSceneExport.rgbComparison(
                "Farbtest", "Loft Licht", "loft licht", rgb(), "#0000FF"
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            GoogleHomeSceneExport.rgbComparison(
                "Farbtest", "Hey Google Loft", "Loft Test", rgb(), "#0000FF"
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            GoogleHomeSceneExport.rgbComparison(
                "Farbtest", "Loft Test", "Loft Test\nactions: []", rgb(), "#0000FF"
            )
        }
    }

    @Test fun comparisonRejectsOffAndKelvinInsteadOfChangingModes() {
        assertThrows(IllegalArgumentException::class.java) {
            GoogleHomeSceneExport.rgbComparison(
                "Farbtest", "Loft A", "Loft B", HomeLamp("Flur - Flur", false), "#FF0000"
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            GoogleHomeSceneExport.rgbComparison(
                "Farbtest", "Loft A", "Loft B",
                HomeLamp("Flur - Flur", true, 45, HomeColor.Temperature(2700)), "#FF0000"
            )
        }
    }

    @Test fun comparisonKeepsBothBrightnessValuesIdenticalAndIsNotAPowerRestore() {
        val export = GoogleHomeSceneExport.rgbComparison(
            "Farbtest", "Loft Test A", "Loft Test B",
            rgb(brightness = 23, rgb = 0x000005), "0000AF"
        )
        assertEquals(2, Regex("brightness: 23").findAll(export.yaml).count())
        assertFalse(export.yaml.contains("brightness: 100"))
        assertTrue(export.yaml.contains("spectrumRGB: \"000005\""))
        assertTrue(export.yaml.contains("spectrumRGB: \"0000AF\""))
        assertFalse(export.yaml.contains("on: false"))
        assertTrue(export.warnings.any { it.contains("Zurücksetzen") })
    }

}