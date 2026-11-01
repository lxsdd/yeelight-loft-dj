package io.github.lxsdd.yeelightloftdj

import org.junit.Assert.*
import org.junit.Test

class CeilingCurrentDiagnosticTest {
    @Test fun localLanAvailableCannotBeInferredFromWifiAssociationAlone() {
        val a = CeilingCurrentDiagnostic.evaluate(CeilingCurrentEvidence(
            association = ApAssociationNow.PRESENT,
            lanService = LocalServiceNow.REACHABLE,
            timing = ObservationTiming.SAME_MOMENT,
        ))
        assertEquals(CeilingCurrentFinding.WLAN_PRESENT_LOCAL_SERVICE_REACHABLE, a.finding)
        val b = CeilingCurrentDiagnostic.evaluate(CeilingCurrentEvidence(
            association = ApAssociationNow.PRESENT,
            timing = ObservationTiming.SAME_MOMENT,
        ))
        assertEquals(CeilingCurrentFinding.WLAN_PRESENT_SERVICE_UNVERIFIED, b.finding)
    }

    @Test fun oldJoinWithoutVisibleDisconnectNeverMeansCurrentlyOnline() {
        for (complete in listOf(false, true)) {
            val result = CeilingCurrentDiagnostic.evaluate(CeilingCurrentEvidence(
                recentRegistrationInLog = true,
                disconnectStillVisibleInLog = false,
                eventHistoryComplete = complete,
            ))
            assertEquals(CeilingCurrentFinding.CURRENT_WLAN_UNKNOWN, result.finding)
            assertFalse(result.currentWifiAbsenceSupported)
            assertFalse(result.firmwareFailureProven)
        }
    }

    @Test fun currentRouterAssociationOverridesMissingHistoricDisconnectWithoutClaimingService() {
        val result = CeilingCurrentDiagnostic.evaluate(CeilingCurrentEvidence(
            recentRegistrationInLog = true, association = ApAssociationNow.PRESENT,
            timing = ObservationTiming.SAME_MOMENT,
        ))
        assertEquals(CeilingCurrentFinding.WLAN_PRESENT_SERVICE_UNVERIFIED, result.finding)
        assertTrue(result.note.contains("nicht geprüft"))
    }

    @Test fun currentRouterAbsenceIsOnlySupportedWhenObservationIsContemporaneous() {
        val result = CeilingCurrentDiagnostic.evaluate(CeilingCurrentEvidence(
            association = ApAssociationNow.ABSENT, timing = ObservationTiming.SAME_MOMENT,
        ))
        assertEquals(CeilingCurrentFinding.CURRENT_WLAN_ABSENT, result.finding)
        assertTrue(result.currentWifiAbsenceSupported)
        assertFalse(result.firmwareFailureProven)
    }

    @Test fun staleCurrentStateAndDifferentTimeAreNotProofOfWlanLoss() {
        for (timing in listOf(ObservationTiming.UNKNOWN, ObservationTiming.DIFFERENT_TIMES)) {
            val result = CeilingCurrentDiagnostic.evaluate(CeilingCurrentEvidence(
                association = ApAssociationNow.ABSENT, timing = timing,
            ))
            assertEquals(CeilingCurrentFinding.UNSYNCHRONIZED_EVIDENCE, result.finding)
            assertFalse(result.currentWifiAbsenceSupported)
        }
    }

    @Test fun connectedButAppFailsIsNotAutomaticallyCloudOrFirmwareBug() {
        val result = CeilingCurrentDiagnostic.evaluate(CeilingCurrentEvidence(
            association = ApAssociationNow.PRESENT, yeelightControl = YeelightControlNow.FAILS,
            timing = ObservationTiming.SAME_MOMENT,
        ))
        assertEquals(CeilingCurrentFinding.WLAN_PRESENT_APP_FAILS_PATH_UNKNOWN, result.finding)
        assertFalse(result.firmwareFailureProven)
    }

    @Test fun connectedButServiceUnreachableIsDistinctFromWifiDisconnect() {
        val result = CeilingCurrentDiagnostic.evaluate(CeilingCurrentEvidence(
            association = ApAssociationNow.PRESENT,
            lanService = LocalServiceNow.UNREACHABLE,
            timing = ObservationTiming.SAME_MOMENT,
        ))
        assertEquals(CeilingCurrentFinding.WLAN_PRESENT_LOCAL_SERVICE_UNREACHABLE, result.finding)
        assertFalse(result.currentWifiAbsenceSupported)
    }

    @Test fun connectedWithLocalServiceButAppFailsRequiresAppCloudReview() {
        val result = CeilingCurrentDiagnostic.evaluate(CeilingCurrentEvidence(
            association = ApAssociationNow.PRESENT,
            lanService = LocalServiceNow.REACHABLE,
            yeelightControl = YeelightControlNow.FAILS,
            timing = ObservationTiming.SAME_MOMENT,
        ))
        assertEquals(CeilingCurrentFinding.WLAN_PRESENT_APP_FAILS_BUT_LOCAL_SERVICE_REACHABLE,
            result.finding)
        assertFalse(result.firmwareFailureProven)
    }

    @Test fun quotaMustPreventAdditionalLanTestsRegardlessOfAppFailure() {
        val result = CeilingCurrentDiagnostic.evaluate(CeilingCurrentEvidence(
            association = ApAssociationNow.PRESENT,
            lanService = LocalServiceNow.QUOTA_REJECTED,
            yeelightControl = YeelightControlNow.FAILS,
            timing = ObservationTiming.SAME_MOMENT,
        ))
        assertEquals(CeilingCurrentFinding.LAN_QUOTA_STOP, result.finding)
        assertTrue(result.note.contains("keine weiteren"))
    }

    @Test fun opposedRouterAndLanReadingsRequireRecheckNotAutomaticActions() {
        val result = CeilingCurrentDiagnostic.evaluate(CeilingCurrentEvidence(
            association = ApAssociationNow.ABSENT,
            lanService = LocalServiceNow.REACHABLE,
            timing = ObservationTiming.SAME_MOMENT,
        ))
        assertEquals(CeilingCurrentFinding.CONTRADICTORY_OBSERVATIONS, result.finding)
        assertFalse(result.currentWifiAbsenceSupported)
    }

    @Test fun appWorksOnlyDescribesCurrentMomentNotPastCause() {
        val result = CeilingCurrentDiagnostic.evaluate(CeilingCurrentEvidence(
            association = ApAssociationNow.PRESENT,
            yeelightControl = YeelightControlNow.WORKS,
            timing = ObservationTiming.SAME_MOMENT,
        ))
        assertEquals(CeilingCurrentFinding.WLAN_PRESENT_APP_WORKS, result.finding)
        assertFalse(result.firmwareFailureProven)
    }

    @Test fun missingDisconnectNeverOverridesARealCurrentAssociation() {
        val result = CeilingCurrentDiagnostic.evaluate(CeilingCurrentEvidence(
            recentRegistrationInLog = true, disconnectStillVisibleInLog = false,
            eventHistoryComplete = true,
            association = ApAssociationNow.ABSENT,
            timing = ObservationTiming.SAME_MOMENT,
        ))
        assertEquals(CeilingCurrentFinding.CURRENT_WLAN_ABSENT, result.finding)
    }
}
