package io.github.lxsdd.yeelightloftdj

import org.junit.Assert.*
import org.junit.Test

class CeilingWifiTimelineTest {
    private fun sequence(
        timestamps: List<Long>, rejoinAfterMs: Long = 5_000L,
    ) = timestamps.flatMap { ts -> listOf(
        CeilingApEvent(ts, CeilingApEventKind.DISCONNECTED),
        CeilingApEvent(ts + rejoinAfterMs, CeilingApEventKind.RECONNECTED),
    ) }

    @Test fun hourlyFourDropEventsProduceEvidenceNotFirmwareVerdict() {
        val base = 1_800_000_000_000L
        val r = CeilingWifiTimeline.analyze(sequence((0L..3L).map { base + it * 3_600_000L }))
        assertEquals(CeilingWifiPattern.PERIODIC_NEAR_HOURLY, r.pattern)
        assertEquals(4, r.disconnectCount)
        assertEquals(listOf(60L, 60L, 60L), r.intervalsMinutes)
        assertFalse(r.provesFirmwareBug)
    }

    @Test fun moderateJitterStillFitsButLargeVariationDoesNot() {
        val base = 1_800_000_000_000L
        val r = CeilingWifiTimeline.analyze(
            sequence(listOf(base, base+3_480_000L, base+7_170_000L, base+10_800_000L)))
        assertEquals(CeilingWifiPattern.PERIODIC_NEAR_HOURLY, r.pattern)
        val notHourly = CeilingWifiTimeline.analyze(
            sequence(listOf(base, base+3_600_000L, base+7_200_000L, base+14_400_000L)))
        assertEquals(CeilingWifiPattern.IRREGULAR_DISCONNECTS, notHourly.pattern)
    }

    @Test fun twoOrThreeDropsCannotEstablishPeriodicity() {
        val base = 1_800_000_000_000L
        for (count in 0..3) {
            assertEquals(CeilingWifiPattern.INSUFFICIENT_EVIDENCE,
                CeilingWifiTimeline.analyze(sequence((0 until count)
                    .map { base + it * 3_600_000L })).pattern)
        }
    }

    @Test fun sameEventDuplicatesOrNonmonotonicTimestampsAreInvalid() {
        val base = 1_800_000_000_000L
        assertEquals(CeilingWifiPattern.INVALID_OR_MIXED_EVENTS,
            CeilingWifiTimeline.analyze(listOf(
                CeilingApEvent(base, CeilingApEventKind.DISCONNECTED),
                CeilingApEvent(base + 50, CeilingApEventKind.DISCONNECTED))).pattern)
        assertEquals(CeilingWifiPattern.INVALID_OR_MIXED_EVENTS,
            CeilingWifiTimeline.analyze(listOf(
                CeilingApEvent(base + 1, CeilingApEventKind.RECONNECTED),
                CeilingApEvent(base, CeilingApEventKind.DISCONNECTED))).pattern)
        assertEquals(CeilingWifiPattern.INVALID_OR_MIXED_EVENTS,
            CeilingWifiTimeline.analyze(listOf(
                CeilingApEvent(-1, CeilingApEventKind.DISCONNECTED))).pattern)
    }

    @Test fun gapsOfTwoHoursAreNotMistakenForHourly() {
        val base = 1_800_000_000_000L
        assertEquals(CeilingWifiPattern.IRREGULAR_DISCONNECTS,
            CeilingWifiTimeline.analyze(sequence((0L..3L)
                .map { base + it * 7_200_000L })).pattern)
    }

    @Test fun rejoinAloneNeverProvesDisconnectionPeriod() {
        val base = 1_800_000_000_000L
        assertEquals(CeilingWifiPattern.INVALID_OR_MIXED_EVENTS,
            CeilingWifiTimeline.analyze((0L..3L).map {
                CeilingApEvent(base + it * 3_600_000L, CeilingApEventKind.RECONNECTED)
            }).pattern)
    }

    @Test fun routerWideSimultaneousLossIsCorrelatedNotRootCause() {
        val base = 1_800_000_000_000L
        val a = sequence((0L..3L).map { base + it * 3_600_000L })
        val b = sequence((0L..3L).map { base + it * 3_600_000L + 15_000L })
        assertEquals(CeilingWifiCrossDevice.SIMULTANEOUS_AP_EVENTS,
            CeilingWifiTimeline.compare(a, b))
    }

    @Test fun nonOverlappingDevicesAreDifferentFaultPattern() {
        val base = 1_800_000_000_000L
        val a = sequence((0L..3L).map { base + it * 3_600_000L })
        val b = sequence((0L..3L).map { base + it * 3_600_000L + 600_000L })
        assertEquals(CeilingWifiCrossDevice.NOT_SIMULTANEOUS,
            CeilingWifiTimeline.compare(a, b))
    }

    @Test fun tooFewDeviceEventsOrInvalidThresholdIsUnknown() {
        val base = 1_800_000_000_000L
        val a = sequence((0L..1L).map { base + it * 3_600_000L })
        assertEquals(CeilingWifiCrossDevice.UNKNOWN,
            CeilingWifiTimeline.compare(a, a))
        assertEquals(CeilingWifiCrossDevice.UNKNOWN,
            CeilingWifiTimeline.compare(sequence((0L..3L).map {
                base + it*3_600_000L
            }), sequence((0L..3L).map { base + it*3_600_000L }), -1))
    }

    @Test fun absoluteEpochMillisAreNotVulnerableToDstWallClockShift() {
        val beforeDst = 1_783_000_000_000L
        val result = CeilingWifiTimeline.analyze(sequence((0L..3L).map {
            beforeDst + it * 3_600_000L
        }))
        assertEquals(CeilingWifiPattern.PERIODIC_NEAR_HOURLY, result.pattern)
    }
}
