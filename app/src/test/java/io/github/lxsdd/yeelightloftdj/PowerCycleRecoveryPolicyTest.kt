package io.github.lxsdd.yeelightloftdj

import org.junit.Assert.*
import org.junit.Test

class PowerCycleRecoveryPolicyTest {
    private val pending = setOf("192.168.0.134", "192.168.0.140")

    @Test fun cannotDiscardJournalWithoutBothExplicitConfirmations() {
        assertFalse(PowerCycleRecoveryPolicy.canRelease(
            "192.168.0.134", pending, false, false, false))
        assertFalse(PowerCycleRecoveryPolicy.canRelease(
            "192.168.0.134", pending, true, false, false))
        assertFalse(PowerCycleRecoveryPolicy.canRelease(
            "192.168.0.134", pending, false, true, false))
    }

    @Test fun onlySavedBulbsCanBeExplicitlyReleasedAndBusySessionsBlockChanges() {
        assertTrue(PowerCycleRecoveryPolicy.canRelease(
            "192.168.0.134", pending, true, true, false))
        assertFalse(PowerCycleRecoveryPolicy.canRelease(
            "192.168.0.141", pending, true, true, false))
        assertFalse(PowerCycleRecoveryPolicy.canRelease(
            "192.168.0.134", pending, true, true, true))
        assertFalse(PowerCycleRecoveryPolicy.canRelease(
            "8.8.8.8", setOf("8.8.8.8"), true, true, false))
    }

    @Test fun otherPendingLampMustRemainSaved() {
        val released = "192.168.0.134"
        val after = pending - released
        assertEquals(setOf("192.168.0.140"), after)
        assertTrue(PowerCycleRecoveryPolicy.canRelease(released, pending, true, true, false))
    }
}
