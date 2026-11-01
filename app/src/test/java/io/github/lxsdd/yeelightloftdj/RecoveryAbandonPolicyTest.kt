package io.github.lxsdd.yeelightloftdj

import org.junit.Assert.*
import org.junit.Test

class RecoveryAbandonPolicyTest {
    private val pending = setOf("192.168.0.134", "192.168.0.140")

    @Test fun cannotDiscardUntilUserUnderstandsBothConsequences() {
        assertFalse(RecoveryAbandonPolicy.canDiscard(
            "192.168.0.134", pending, false, false, false))
        assertFalse(RecoveryAbandonPolicy.canDiscard(
            "192.168.0.134", pending, true, false, false))
        assertFalse(RecoveryAbandonPolicy.canDiscard(
            "192.168.0.134", pending, false, true, false))
        assertTrue(RecoveryAbandonPolicy.canDiscard(
            "192.168.0.134", pending, true, true, false))
    }

    @Test fun neverDiscardUnrelatedOrUnsafeEntriesOrWhileActive() {
        assertFalse(RecoveryAbandonPolicy.canDiscard(
            "192.168.0.141", pending, true, true, false))
        assertFalse(RecoveryAbandonPolicy.canDiscard(
            "192.168.0.134", pending, true, true, true))
        assertFalse(RecoveryAbandonPolicy.canDiscard(
            "8.8.8.8", setOf("8.8.8.8"), true, true, false))
    }

    @Test fun removingOneDoesNotEraseOtherPendingBulbs() {
        val remaining = pending - "192.168.0.134"
        assertEquals(setOf("192.168.0.140"), remaining)
    }

    @Test fun noPhysicalRestartClaimRequiredForHonestLocalDiscard() {
        assertTrue(RecoveryAbandonPolicy.canDiscard(
            "192.168.0.134", pending, true, true, false))
    }
}
