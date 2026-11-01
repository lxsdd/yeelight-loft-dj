package io.github.lxsdd.yeelightloftdj

import org.junit.Assert.*
import org.junit.Test

class RecoveryReadPolicyTest {
    private fun snap(on: Boolean, color: Int = 0xFF0000) =
        LampSnapshot(on,50,1,color,null,null,null)

    @Test fun neverClearRunningOrUnreadableFlow() {
        assertEquals(RecoveryReadDecision.FLOW_STILL_RUNNING,
            RecoveryReadPolicy.decide(true,snap(false),null,false))
        assertEquals(RecoveryReadDecision.UNKNOWN,
            RecoveryReadPolicy.decide(null,snap(false),null,false))
        assertFalse(RecoveryReadPolicy.canClear(RecoveryReadDecision.UNKNOWN))
    }
    @Test fun stoppedFlowWithoutSavedOriginalCanReleaseJustItsOwnMarker() {
        val d = RecoveryReadPolicy.decide(false,snap(true),null,false)
        assertEquals(RecoveryReadDecision.FLOW_STOPPED_NO_SNAPSHOT,d)
        assertTrue(RecoveryReadPolicy.canClear(d))
    }
    @Test fun savedOriginalMustMatchExactlyForAutomaticRelease() {
        assertEquals(RecoveryReadDecision.ALREADY_RESTORED,
            RecoveryReadPolicy.decide(false,snap(true),snap(true),false))
        assertEquals(RecoveryReadDecision.RESTORE_REQUIRED,
            RecoveryReadPolicy.decide(false,snap(true,0x00FF00),snap(true),false))
        assertEquals(RecoveryReadDecision.RESTORE_REQUIRED,
            RecoveryReadPolicy.decide(false,snap(false),snap(true),false))
    }
    @Test fun offOriginalMayIgnoreStaleColorRegisters() {
        assertTrue(RecoveryReadPolicy.matches(snap(false,0xFF0000),
            snap(false,0x00FF00)))
    }
    @Test fun scannerOffIsIndependentOfUnsupportedFlowingProperty() {
        assertEquals(RecoveryReadDecision.SCANNER_OFF,
            RecoveryReadPolicy.decide(null,snap(false),null,true))
        assertEquals(RecoveryReadDecision.UNKNOWN,
            RecoveryReadPolicy.decide(null,snap(true),null,true))
    }
    @Test fun failedMusicStopNeverAutoClearsEvenMatchingReadback() {
        assertFalse(RecoveryReadPolicy.canClear(
            RecoveryReadDecision.ALREADY_RESTORED, musicSessionUnconfirmed = true))
        assertFalse(RecoveryReadPolicy.canClear(
            RecoveryReadDecision.SCANNER_OFF, musicSessionUnconfirmed = true))
        assertTrue(RecoveryReadPolicy.canClear(
            RecoveryReadDecision.ALREADY_RESTORED, musicSessionUnconfirmed = false))
    }

    @Test fun uncertainMusicOrFlowMustNotTriggerStaticReplay() {
        assertTrue(RecoveryReadPolicy.safeToRestoreAfterLostMusicChannel(false, false))
        assertFalse(RecoveryReadPolicy.safeToRestoreAfterLostMusicChannel(true, false))
        assertFalse(RecoveryReadPolicy.safeToRestoreAfterLostMusicChannel(false, true))
        assertFalse(RecoveryReadPolicy.safeToRestoreAfterLostMusicChannel(null, false))
        assertFalse(RecoveryReadPolicy.safeToRestoreAfterLostMusicChannel(false, null))
    }

    @Test fun musicRecoveryNeedsExplicitMusicOffAndStaticMatch() {
        assertTrue(RecoveryReadPolicy.musicStillUnconfirmed(true, null))
        assertTrue(RecoveryReadPolicy.musicStillUnconfirmed(true, true))
        assertFalse(RecoveryReadPolicy.musicStillUnconfirmed(true, false))
        assertFalse(RecoveryReadPolicy.musicStillUnconfirmed(false, null))
        assertTrue(RecoveryReadPolicy.canClear(RecoveryReadDecision.ALREADY_RESTORED,
            musicSessionUnconfirmed = RecoveryReadPolicy.musicStillUnconfirmed(true, false)))
        assertFalse(RecoveryReadPolicy.canClear(RecoveryReadDecision.RESTORE_REQUIRED,
            musicSessionUnconfirmed = RecoveryReadPolicy.musicStillUnconfirmed(true, false)))
        assertFalse(RecoveryReadPolicy.canClear(RecoveryReadDecision.FLOW_STILL_RUNNING,
            musicSessionUnconfirmed = RecoveryReadPolicy.musicStillUnconfirmed(true, false)))
    }

    @Test fun unknownPropertyDoesNotReleaseSavedSnapshot() {
        assertEquals(RecoveryReadDecision.UNKNOWN,
            RecoveryReadPolicy.decide(false,null,snap(true),false))
        assertEquals(RecoveryReadDecision.UNKNOWN,
            RecoveryReadPolicy.decide(null,snap(true),snap(true),false))
    }
}
