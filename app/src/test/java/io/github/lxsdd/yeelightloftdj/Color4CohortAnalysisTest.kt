package io.github.lxsdd.yeelightloftdj

import org.junit.Assert.*
import org.junit.Test

class Color4CohortAnalysisTest {
    private fun lamp(
        slot: Int, result: Color4LanOutcome,
        runtime: Color4RuntimeEvidence = Color4RuntimeEvidence.WALL_POWER_OVER_50_DAYS_ONLY,
        firmware: String = "2.1.7_0039",
        exposure: Color4Exposure = Color4Exposure.UNKNOWN,
    ) = Color4CohortEntry(slot, firmware, runtime, result, exposure)

    @Test fun allWallPoweredButOnlyTwoQuotasIsNotProofOfCommonCrash() {
        val data = (1..15).map {
            lamp(it, if (it <= 2) Color4LanOutcome.EXPLICIT_CLIENT_QUOTA_EXCEEDED
                 else Color4LanOutcome.NOT_OBSERVED)
        }
        val result = Color4CohortAnalysis.analyze(data)
        assertEquals(Color4CohortConclusion.WRAP_HYPOTHESIS_COMPATIBLE_BUT_NOT_PROVEN,
            result.conclusion)
        assertEquals(2, result.reportedQuotaCount)
        assertEquals(13, result.untestedOrCloudOnlyCount)
        assertEquals(0, result.observedWorkingLanCount)
        assertFalse(result.provenColor4FirmwareCause)
    }

    @Test fun trulyWorkingLanPeerMakesPowerTimeAloneNonDiscriminating() {
        val r = Color4CohortAnalysis.analyze(listOf(
            lamp(1, Color4LanOutcome.EXPLICIT_CLIENT_QUOTA_EXCEEDED),
            lamp(2, Color4LanOutcome.LAN_COMMAND_ACKNOWLEDGED)
        ))
        assertEquals(Color4CohortConclusion.POWER_TIME_ALONE_NOT_DISCRIMINATING, r.conclusion)
        assertFalse(r.allEntriesHaveConfirmedFirmwareUptime)
        assertFalse(r.provenColor4FirmwareCause)
    }

    @Test fun cloudOrGoogleReachableDoesNotCountAsLocalLanAcknowledged() {
        val r = Color4CohortAnalysis.analyze(listOf(
            lamp(1, Color4LanOutcome.EXPLICIT_CLIENT_QUOTA_EXCEEDED),
            lamp(2, Color4LanOutcome.NOT_OBSERVED)
        ))
        assertEquals(0, r.observedWorkingLanCount)
        assertEquals(1, r.untestedOrCloudOnlyCount)
    }

    @Test fun firmwareUptimeIsDistinctFromWallPower() {
        val r = Color4CohortAnalysis.analyze(listOf(
            lamp(1, Color4LanOutcome.EXPLICIT_CLIENT_QUOTA_EXCEEDED,
                runtime = Color4RuntimeEvidence.VERIFIED_FIRMWARE_UPTIME_OVER_49_DAYS_17_HOURS),
            lamp(2, Color4LanOutcome.NOT_OBSERVED,
                runtime = Color4RuntimeEvidence.WALL_POWER_OVER_50_DAYS_ONLY)
        ))
        assertFalse(r.allEntriesHaveConfirmedFirmwareUptime)
    }

    @Test fun allVerifiedCpuUptimeStillDoesNotProveOriginalBinaryBug() {
        val r = Color4CohortAnalysis.analyze(listOf(
            lamp(1, Color4LanOutcome.EXPLICIT_CLIENT_QUOTA_EXCEEDED,
                runtime = Color4RuntimeEvidence.VERIFIED_FIRMWARE_UPTIME_OVER_49_DAYS_17_HOURS),
            lamp(2, Color4LanOutcome.LAN_COMMAND_ACKNOWLEDGED,
                runtime = Color4RuntimeEvidence.VERIFIED_FIRMWARE_UPTIME_OVER_49_DAYS_17_HOURS)
        ))
        assertTrue(r.allEntriesHaveConfirmedFirmwareUptime)
        assertFalse(r.provenColor4FirmwareCause)
    }

    @Test fun unknownFirmwareOrOtherModelCannotBeConfidentlyGrouped() {
        val r = Color4CohortAnalysis.analyze(listOf(
            lamp(1, Color4LanOutcome.EXPLICIT_CLIENT_QUOTA_EXCEEDED),
            lamp(2, Color4LanOutcome.LAN_COMMAND_ACKNOWLEDGED, firmware="2.1.7_0038")
        ))
        assertEquals(Color4CohortConclusion.UNKNOWN_FIRMWARE_OR_TOO_FEW_OBSERVATIONS,
            r.conclusion)
    }

    @Test fun anotherNetworkFailureRequiresDifferentDiagnosis() {
        val r = Color4CohortAnalysis.analyze(listOf(
            lamp(1, Color4LanOutcome.EXPLICIT_CLIENT_QUOTA_EXCEEDED),
            lamp(2, Color4LanOutcome.OTHER_LAN_ERROR)
        ))
        assertEquals(Color4CohortConclusion.CONTRADICTING_OR_MIXED_EVIDENCE, r.conclusion)
    }

    @Test fun localTrafficLevelDoesNotAutomaticallyChangeRootCause() {
        val r = Color4CohortAnalysis.analyze(listOf(
            lamp(1, Color4LanOutcome.EXPLICIT_CLIENT_QUOTA_EXCEEDED,
                exposure = Color4Exposure.MULTIPLE_LOCAL_COMMANDS_OR_CLIENTS),
            lamp(2, Color4LanOutcome.NOT_OBSERVED,
                exposure = Color4Exposure.LOW_OBSERVED_LOCAL_TRAFFIC)
        ))
        assertEquals(Color4CohortConclusion.WRAP_HYPOTHESIS_COMPATIBLE_BUT_NOT_PROVEN, r.conclusion)
    }

    @Test fun emptyCohortReturnsInsufficientEvidence() {
        val r = Color4CohortAnalysis.analyze(emptyList())
        assertEquals(Color4CohortConclusion.UNKNOWN_FIRMWARE_OR_TOO_FEW_OBSERVATIONS, r.conclusion)
    }

    @Test fun repeatedOrInvalidSlotsAreRejected() {
        for (list in listOf(
            listOf(lamp(1, Color4LanOutcome.NOT_OBSERVED),
                lamp(1, Color4LanOutcome.NOT_OBSERVED)),
            listOf(lamp(0, Color4LanOutcome.NOT_OBSERVED))
        )) {
            try { Color4CohortAnalysis.analyze(list); fail("expected") }
            catch (_: IllegalArgumentException) {}
        }
    }

    @Test fun tooManyDevicesRejectedWithoutOverflow() {
        val list = (1..65).map { lamp(it, Color4LanOutcome.NOT_OBSERVED) }
        try { Color4CohortAnalysis.analyze(list); fail("expected") }
        catch (_: IllegalArgumentException) {}
    }
}
