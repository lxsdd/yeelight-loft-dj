package io.github.lxsdd.yeelightloftdj

import org.junit.Assert.*
import org.junit.Test

class Ceiling1NetworkPathDiagnosticTest {
    private fun sample(
        ap: Ceiling1ApState = Ceiling1ApState.ASSOCIATED_NOW,
        app: Ceiling1AppState = Ceiling1AppState.UNKNOWN,
        lan: Ceiling1LanState = Ceiling1LanState.UNKNOWN,
        ble: Ceiling1BleState = Ceiling1BleState.UNKNOWN,
        timing: Ceiling1DiagnosticTiming = Ceiling1DiagnosticTiming.CONTEMPORANEOUS,
    ) = Ceiling1NetworkEvidence(ap=ap,app=app,lan=lan,ble=ble,timing=timing)

    private fun finding(e: Ceiling1NetworkEvidence) =
        Ceiling1NetworkPathDiagnostic.evaluate(e).finding

    @Test fun historicalWifiJoinAndDhcpLeaseAreNotCurrentConnection() {
        val e=Ceiling1NetworkEvidence(historicAssociationLogPresent=true,
            historicDisconnectLogPresent=false,dhcpLeaseExists=true)
        assertEquals(Ceiling1NetworkFinding.NEED_CURRENT_AP_EVIDENCE,finding(e))
    }
    @Test fun visibleHistoricalDisconnectDoesNotImplyCurrentAbsence() {
        assertEquals(Ceiling1NetworkFinding.NEED_CURRENT_AP_EVIDENCE,
            finding(Ceiling1NetworkEvidence(historicDisconnectLogPresent=true)))
    }
    @Test fun trueWlanAbsenceAndBleResponseIdentifiesDifferentPaths() {
        val e=sample(ap=Ceiling1ApState.NOT_ASSOCIATED_NOW,
            app=Ceiling1AppState.CURRENT_CONTROL_FAILS,ble=Ceiling1BleState.CURRENT_BLE_WORKS)
        val result=Ceiling1NetworkPathDiagnostic.evaluate(e)
        assertEquals(Ceiling1NetworkFinding.WLAN_ASSOCIATION_LOST_BLE_ALIVE,result.finding)
        assertTrue(result.wlanDisassociationSupported)
        assertFalse(result.firmwareFailureProven)
        assertFalse(result.automaticRecoveryPermitted)
        assertFalse(result.color4QuotaDiagnosisApplies)
    }
    @Test fun noBleTestCannotBePromotedToBleAlive() {
        assertEquals(Ceiling1NetworkFinding.WLAN_ASSOCIATION_LOST_BLE_UNVERIFIED,
            finding(sample(ap=Ceiling1ApState.NOT_ASSOCIATED_NOW)))
    }
    @Test fun cloudWorkingWhileApAbsentIsContradictory() {
        val r=Ceiling1NetworkPathDiagnostic.evaluate(sample(
            ap=Ceiling1ApState.NOT_ASSOCIATED_NOW,
            app=Ceiling1AppState.CURRENT_CONTROL_WORKS))
        assertEquals(Ceiling1NetworkFinding.OBSERVATIONS_CONTRADICT,r.finding)
        assertFalse(r.wlanDisassociationSupported)
    }
    @Test fun localLanSuccessWithAbsentApAlsoContradicts() {
        assertEquals(Ceiling1NetworkFinding.OBSERVATIONS_CONTRADICT,
            finding(sample(ap=Ceiling1ApState.NOT_ASSOCIATED_NOW,
                lan=Ceiling1LanState.CURRENT_LAN_CONFIRMED)))
    }
    @Test fun staleReadingsNeverProveWiFiLoss() {
        for (time in listOf(Ceiling1DiagnosticTiming.UNKNOWN,
            Ceiling1DiagnosticTiming.STALE_OR_DIFFERENT_TIMES)) {
            val r=Ceiling1NetworkPathDiagnostic.evaluate(sample(
                ap=Ceiling1ApState.NOT_ASSOCIATED_NOW,timing=time))
            assertEquals(Ceiling1NetworkFinding.OBSERVATIONS_NOT_SIMULTANEOUS,r.finding)
            assertFalse(r.wlanDisassociationSupported)
        }
    }
    @Test fun associatedLanWorkingAppFailsMeansServiceCloudPath() {
        assertEquals(Ceiling1NetworkFinding.WLAN_ASSOCIATED_APP_CLOUD_PATH_DOWN,
            finding(sample(app=Ceiling1AppState.CURRENT_CONTROL_FAILS,
                lan=Ceiling1LanState.CURRENT_LAN_CONFIRMED)))
    }
    @Test fun associatedLanFailsAppWorksMeansLocalPath() {
        assertEquals(Ceiling1NetworkFinding.WLAN_ASSOCIATED_LOCAL_PATH_DOWN,
            finding(sample(app=Ceiling1AppState.CURRENT_CONTROL_WORKS,
                lan=Ceiling1LanState.CURRENT_LAN_FAILS)))
    }
    @Test fun associatedButBothControlPathsFailIsNotWifiDisassociation() {
        val r=Ceiling1NetworkPathDiagnostic.evaluate(sample(
            app=Ceiling1AppState.CURRENT_CONTROL_FAILS,
            lan=Ceiling1LanState.CURRENT_LAN_FAILS))
        assertEquals(Ceiling1NetworkFinding.WLAN_ASSOCIATED_BOTH_PATHS_UNAVAILABLE,r.finding)
        assertFalse(r.wlanDisassociationSupported)
    }
    @Test fun appWorksButLANUntestedCannotClaimLocalHealth() {
        assertEquals(Ceiling1NetworkFinding.WLAN_ASSOCIATED_CLOUD_ONLY_NO_LAN_PROOF,
            finding(sample(app=Ceiling1AppState.CURRENT_CONTROL_WORKS)))
    }
    @Test fun appFailsWithoutLanEvidenceIsUnresolved() {
        assertEquals(Ceiling1NetworkFinding.WLAN_ASSOCIATED_APP_PATH_UNRESOLVED,
            finding(sample(app=Ceiling1AppState.CURRENT_CONTROL_FAILS)))
    }
    @Test fun healthyStateDoesNotProveHistoricalFirmwareFix() {
        val r=Ceiling1NetworkPathDiagnostic.evaluate(sample(
            app=Ceiling1AppState.CURRENT_CONTROL_WORKS,
            lan=Ceiling1LanState.CURRENT_LAN_CONFIRMED))
        assertEquals(Ceiling1NetworkFinding.WLAN_ASSOCIATED_HEALTHY_OBSERVED,r.finding)
        assertFalse(r.firmwareFailureProven)
    }
    @Test fun associatedWithoutAnyControlTestsIsUnknownPath() {
        assertEquals(Ceiling1NetworkFinding.WLAN_ASSOCIATED_PATHS_NOT_CHECKED,
            finding(sample()))
    }
    @Test fun bleWorksWhileWlanAssociatedDoesNotProveCloud() {
        assertEquals(Ceiling1NetworkFinding.WLAN_ASSOCIATED_PATHS_NOT_CHECKED,
            finding(sample(ble=Ceiling1BleState.CURRENT_BLE_WORKS)))
    }
    @Test fun noQuotaShortCircuitEvenWithLanFailure() {
        val e=sample(lan=Ceiling1LanState.CURRENT_LAN_FAILS)
        val r=Ceiling1NetworkPathDiagnostic.evaluate(e)
        assertEquals(Ceiling1NetworkFinding.WLAN_ASSOCIATED_PATHS_NOT_CHECKED,r.finding)
        assertFalse(r.color4QuotaDiagnosisApplies)
    }
}
