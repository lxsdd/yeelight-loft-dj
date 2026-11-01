package io.github.lxsdd.yeelightloftdj

import org.junit.Assert.*
import org.junit.Test

class RecoveryObservationTest {
    @Test fun oneQueryReturnsFlowAndPowerPlusRestorableColors() {
        val obs = YeelightLan.parseRecoveryProperties(
            listOf("0","off","55","1","16711680","4000","45","80"))
        assertEquals(false, obs.flowing)
        assertNotNull(obs.snapshot)
        assertFalse(obs.snapshot!!.isOn)
        assertEquals(55, obs.snapshot!!.brightness)
        assertEquals(0xFF0000, obs.snapshot!!.rgb)
    }
    @Test fun unsupportedFlowPropertyNeverMeansStopped() {
        val obs = YeelightLan.parseRecoveryProperties(
            listOf("","on","75","1","16711680","4000","",""))
        assertNull(obs.flowing)
        assertNotNull(obs.snapshot)
    }
    @Test fun unreadablePowerWillNotBeInterpretedAsRestored() {
        val obs = YeelightLan.parseRecoveryProperties(
            listOf("0","","80","1","16711680","4000","",""))
        assertEquals(false, obs.flowing)
        assertNull(obs.snapshot)
    }
    @Test fun scannerCanSeeOffEvenIfFlowingIsNotSupported() {
        val obs = YeelightLan.parseRecoveryProperties(
            listOf("","off","50","1","0","","",""))
        assertEquals(RecoveryReadDecision.SCANNER_OFF,
            RecoveryReadPolicy.decide(obs.flowing,obs.snapshot,null,true,
                reportedPowerOn=obs.powerOn))
    }

    @Test fun scannerCanClearWithOffPowerEvenWhenBrightnessReturnsZero() {
        val obs = YeelightLan.parseRecoveryProperties(
            listOf("","off","0","","","","",""))
        assertNull(obs.snapshot)
        assertEquals(false, obs.powerOn)
        assertEquals(RecoveryReadDecision.SCANNER_OFF,
            RecoveryReadPolicy.decide(obs.flowing,obs.snapshot,null,true,
                reportedPowerOn=obs.powerOn))
    }

    @Test fun originallyOffCanBeProvenWithoutValidColorSnapshot() {
        val obs = YeelightLan.parseRecoveryProperties(
            listOf("0","off","0","","","","",""))
        val original = LampSnapshot(false,50,1,0xFF0000,null,null,null)
        assertEquals(RecoveryReadDecision.ALREADY_RESTORED,
            RecoveryReadPolicy.decide(obs.flowing,obs.snapshot,original,false,
                reportedPowerOn=obs.powerOn))
    }
}
