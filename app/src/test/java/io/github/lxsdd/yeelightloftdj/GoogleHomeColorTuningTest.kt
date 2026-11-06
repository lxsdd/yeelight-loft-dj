package io.github.lxsdd.yeelightloftdj

import org.junit.Assert.*
import org.junit.Test

class GoogleHomeColorTuningTest {
    private val sofa = HomeLamp("Sofa - Wohnzimmer", true, 45, HomeColor.Rgb(0xFFB36B))
    private val hall = HomeLamp("Flur - Flur", true, 25, HomeColor.Rgb(0x72B9E8))
    private val original = listOf(sofa, hall)

    @Test fun acceptsExplicitHexWithHashAndPreservesOtherBulbs() {
        val changed = GoogleHomeColorTuning.applyOverrides(original, mapOf(sofa.deviceAndRoom to "#ff7700"))
        assertEquals(HomeColor.Rgb(0xFF7700), changed[0].color)
        assertEquals(hall, changed[1])
        assertEquals(HomeColor.Rgb(0xFFB36B), original[0].color)
        assertEquals(45, changed[0].brightness)
    }

    @Test fun acceptsSixHexDigitsIncludingLeadingZeros() {
        val edited = GoogleHomeColorTuning.applyOverrides(listOf(sofa), mapOf(sofa.deviceAndRoom to "0000aF"))
        assertEquals(HomeColor.Rgb(0xAF), edited.single().color)
        val yaml = GoogleHomeSceneExport.staticScene("Test", "Loft Test", edited).yaml
        assertTrue(yaml.contains("spectrumRGB: \"0000AF\""))
    }

    @Test fun noOverrideIsIdentityWithoutLampsOrNetworkChanges() {
        assertEquals(original, GoogleHomeColorTuning.applyOverrides(original, emptyMap()))
    }

    @Test fun rejectsWrongTargetWithoutApplyingOtherEdits() {
        assertThrows(IllegalArgumentException::class.java) {
            GoogleHomeColorTuning.applyOverrides(original, mapOf("Unbekannt - Esszimmer" to "FF0000"))
        }
    }

    @Test fun rejectsInvalidRgbSyntaxAndRgbOutsideRange() {
        for (bad in listOf("", "0xFF7700", "#FFF", "GG8800", "#FF77000", "FF7700\nactions: []")) {
            assertThrows(IllegalArgumentException::class.java) {
                GoogleHomeColorTuning.applyOverrides(listOf(sofa), mapOf(sofa.deviceAndRoom to bad))
            }
        }
    }

    @Test fun rejectsDisabledOrTemperatureDevicesInsteadOfChangingTheirModes() {
        val off = HomeLamp("Aus - Wohnzimmer", false, 30)
        val temp = HomeLamp("Weiss - Flur", true, 35, HomeColor.Temperature(2700))
        for (lamp in listOf(off, temp)) {
            assertThrows(IllegalArgumentException::class.java) {
                GoogleHomeColorTuning.applyOverrides(listOf(lamp), mapOf(lamp.deviceAndRoom to "FF7700"))
            }
        }
    }

    @Test fun voiceStarterKeepsSuccessfulShortPhraseAndDoesNotInsertWakeWord() {
        val yaml = GoogleHomeSceneExport.staticScene("Szenenname", "Loft Test", listOf(sofa)).yaml
        assertTrue(yaml.contains("eventData: query\n      is: \"Loft Test\""))
        assertFalse(yaml.contains("Hey Google"))
    }

    @Test fun voiceStarterRejectsWakeWordPrefixBecauseItIsNotPartOfTheQuery() {
        for (bad in listOf("Hey Google, Loft Test", "Ok Google Loft Test", "Okay Google", "HEY GOOGLE starten")) {
            assertThrows(IllegalArgumentException::class.java) {
                GoogleHomeSceneExport.staticScene("Szenenname", bad, listOf(sofa))
            }
        }
    }
}
