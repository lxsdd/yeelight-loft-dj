package io.github.lxsdd.yeelightloftdj

import org.junit.Assert.*
import org.junit.Test

class FirmwareObservationTest {
    private fun timestamp(minute: Int): String =
        "01.11.2026 %02d:%02d:00".format(10 + minute / 60, minute % 60)
    private fun times(vararg minutes: Int) = minutes.joinToString("\n") { timestamp(it) }

    @Test fun savedCapabilityIsNeverProofOfSuccessfulHandshake() {
        val r = FirmwareInsightReport.summarize("color4", setOf("chroma", "udp_sess_new"))
        assertTrue(r.chroma.contains("v2"))
        assertTrue(r.evidence.contains("nicht live"))
        assertFalse(r.flashAllowed)
    }

    @Test fun cachedUdpSessionWithoutRgbSceneIsNotPresentedAsReady() {
        val result = FirmwareInsightReport.summarize("color4",
            setOf("chroma", "udp_sess_new"))
        assertTrue(result.chroma.contains("RGB-set_scene nicht belegt"))
        assertTrue(result.evidence.contains("nicht live"))
    }

    @Test fun cachedUdpWithSceneStillHasNoHardwareConfirmation() {
        val result = FirmwareInsightReport.summarize("color4",
            setOf("chroma", "udp_sess_new", "set_scene"))
        assertTrue(result.chroma.contains("nur Offline-Simulation"))
        assertTrue(result.chroma.contains("kein Gerätetest"))
        assertFalse(result.flashAllowed)
    }

    @Test fun noSupportMeansUnknownNotUnsupported() {
        val r = FirmwareInsightReport.summarize("color4", emptySet())
        assertTrue(r.chroma.contains("unbekannt"))
        assertFalse(r.flashAllowed)
    }

    @Test fun ceilingControllerReplacementIsNotFlashAuthorization() {
        val r = FirmwareInsightReport.summarize("ceiling1", setOf("get_prop"))
        assertTrue(r.firmware.contains("Controller-Ersatz"))
        assertFalse(r.flashAllowed)
    }

    @Test fun chronologicalOrderCanBeReversedLikeRouterEventLists() {
        val x = CeilingManualTimeline.analyze(times(180,120,60,0))
        assertEquals(CeilingManualStatus.HOURLY_PATTERN, x.status)
        assertEquals(listOf(60L,60L,60L), x.intervalsMinutes)
        assertFalse(x.confirmsFirmwareDefect)
    }

    @Test fun irregularOrMissingEventsNeverProveAnHourlyTimer() {
        assertEquals(CeilingManualStatus.IRREGULAR,
            CeilingManualTimeline.analyze(times(0,60,120,220)).status)
        assertEquals(CeilingManualStatus.TOO_FEW,
            CeilingManualTimeline.analyze(times(0,60,120)).status)
    }

    @Test fun nearHourlyJitterAllowedButTwoHourGapNotHidden() {
        assertEquals(CeilingManualStatus.HOURLY_PATTERN,
            CeilingManualTimeline.analyze(times(0,59,121,181)).status)
        assertEquals(CeilingManualStatus.IRREGULAR,
            CeilingManualTimeline.analyze(times(0,120,240,360)).status)
    }

    @Test fun rawRouterLogAndIpDataAreRejected() {
        assertEquals(CeilingManualStatus.INVALID, CeilingManualTimeline.analyze(
            "01.11.2026 10:00:00 WLAN-Gerät 192.168.1.20 abgemeldet").status)
        assertEquals(CeilingManualStatus.INVALID,
            CeilingManualTimeline.analyze(times(0,60,60,180)).status)
    }

    @Test fun daylightSavingAmbiguityAndNonexistentLocalTimeRejected() {
        assertEquals(CeilingManualStatus.DST_AMBIGUOUS, CeilingManualTimeline.analyze(
            "25.10.2026 02:30:00").status)
        assertEquals(CeilingManualStatus.DST_AMBIGUOUS, CeilingManualTimeline.analyze(
            "29.03.2026 02:30:00").status)
    }

    @Test fun badCalendarAndInputLimitRejected() {
        assertEquals(CeilingManualStatus.INVALID, CeilingManualTimeline.analyze(
            "31.02.2026 10:00:00").status)
        assertEquals(CeilingManualStatus.TOO_LARGE, CeilingManualTimeline.analyze(
            "x".repeat(8193)).status)
        assertEquals(CeilingManualStatus.TOO_LARGE, CeilingManualTimeline.analyze(
            List(129) { "01.11.2026 10:00:00" }.joinToString("\n")).status)
    }

    @Test fun twoDevicesCanShowCoincidenceWithoutProvingCause() {
        assertEquals(CeilingComparison.SIMULTANEOUS,
            CeilingManualTimeline.compare(times(0,60,120,180),times(1,61,121,181)))
        assertEquals(CeilingComparison.INDEPENDENT,
            CeilingManualTimeline.compare(times(0,60,120,180),times(15,75,135,195)))
        assertEquals(CeilingComparison.UNKNOWN,
            CeilingManualTimeline.compare(times(0,60,120,180),""))
    }
}
