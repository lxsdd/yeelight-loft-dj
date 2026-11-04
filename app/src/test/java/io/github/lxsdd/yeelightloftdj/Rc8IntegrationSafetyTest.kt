package io.github.lxsdd.yeelightloftdj

import org.junit.Assert.*
import org.junit.Test

/**
 * Offline RC8 cross-feature safety regression. No Android services, sockets,
 * firmware, cloud credentials, IP addresses or lamp writes are touched here.
 */
class Rc8IntegrationSafetyTest {
    @Test fun allColor4LiveMusicAcceptanceRemainsOff() {
        assertFalse(Color4MusicAcceptancePolicy.LIVE_TEST_APPROVED)
        assertFalse(Color4DualAcceptancePolicy.LIVE_TEST_APPROVED)
    }

    @Test fun GoogleHomeExportStillBuildsWithoutEnablingMusicLiveGates() {
        val exported = GoogleHomeSceneExport.staticScene(
            "Loft Abend", "Loft Test",
            listOf(
                HomeLamp("Couchtisch - Wohnzimmer", true, 45, HomeColor.Rgb(0xFFB36B)),
                HomeLamp("Flur - Flur", false),
            )
        )
        assertTrue(exported.yaml.contains("assistant.event.OkGoogle"))
        assertTrue(exported.yaml.contains("spectrumRGB: \"FFB36B\""))
        assertTrue(exported.yaml.contains("on: false"))
        assertFalse(exported.yaml.contains("assistant.command.OkGoogle"))
        assertFalse(Color4MusicAcceptancePolicy.LIVE_TEST_APPROVED)
        assertFalse(Color4DualAcceptancePolicy.LIVE_TEST_APPROVED)
    }

    @Test fun SingleStarterGoogleHomeCycleHasSixTransitionsAndNoAutonomousLoop() {
        val exported = GoogleHomeSceneExport.rgbAlternatingCycle(
            "Couchtisch Vergleich", "Loft Farbwechsel",
            HomeLamp("Couchtisch - Wohnzimmer", true, 45, HomeColor.Rgb(0xFFB36B)),
            "FF7700"
        )
        assertEquals(1, Regex("assistant.event.OkGoogle").findAll(exported.yaml).count())
        assertEquals(7, Regex("spectrumRGB:").findAll(exported.yaml).count())
        assertEquals(5, Regex("for: 10sec").findAll(exported.yaml).count())
        assertFalse(exported.yaml.contains("assistant.command.OkGoogle"))
        assertFalse(exported.yaml.contains("repeat:"))
        assertFalse(exported.yaml.contains("on: true"))
    }

    @Test fun AmbiguousGoogleHomeTargetsFailWithoutEffectOnColor4SafetyGates() {
        assertThrows(IllegalArgumentException::class.java) {
            GoogleHomeSceneExport.staticScene(
                "Duplicate", "Loft Test",
                listOf(
                    HomeLamp("Billardtisch - Hobbyraum", true, 45, HomeColor.Rgb(0xFF7700)),
                    HomeLamp("Billardtisch - Hobbyraum", true, 45, HomeColor.Rgb(0xFFB36B))
                )
            )
        }
        assertFalse(Color4MusicAcceptancePolicy.LIVE_TEST_APPROVED)
        assertFalse(Color4DualAcceptancePolicy.LIVE_TEST_APPROVED)
    }
}
