package io.github.lxsdd.yeelightloftdj

import org.junit.Assert.*
import org.junit.Test

class PowerVerificationPolicyTest {
    @Test fun replyIsNotTheSameAsObservedPhysicalState() {
        assertEquals(PowerOutcome.ACK_ONLY,
            PowerVerificationPolicy.outcome(true, true, null))
        assertEquals(PowerOutcome.MISMATCH,
            PowerVerificationPolicy.outcome(true, true, false))
        assertEquals(PowerOutcome.VERIFIED,
            PowerVerificationPolicy.outcome(true, false, false))
    }

    @Test fun missingAcknowledgementMustNotBeSilentlyRetriedOrCountedAsSuccess() {
        assertEquals(PowerOutcome.NO_ACK,
            PowerVerificationPolicy.outcome(false, true, true))
        assertFalse(PowerProbe(PowerOutcome.NO_ACK).commandAccepted)
        assertTrue(PowerProbe(PowerOutcome.ACK_ONLY).commandAccepted)
    }

    @Test fun summarySeparatesHealthyUnknownFailedAndProtectedBulbs() {
        val values = listOf(PowerProbe(PowerOutcome.VERIFIED),
            PowerProbe(PowerOutcome.ACK_ONLY),
            PowerProbe(PowerOutcome.NO_ACK),
            PowerProbe(PowerOutcome.PROTECTED))
        val label = PowerVerificationPolicy.summarize(values)
        assertTrue(label.contains("1/4"))
        assertTrue(label.contains("Status unbekannt"))
        assertTrue(label.contains("geschützt"))
        assertFalse(label.contains("4/4 Zustand bestätigt"))
    }

    @Test fun phaseTimingsDoNotClaimHardwareLatencyWithoutReadback() {
        val probe = PowerProbe(PowerOutcome.ACK_ONLY,
            connectMs = 40L, ackMs = 75L, statusMs = null, totalMs = 1800L)
        assertNull(probe.statusMs)
        assertEquals(75L, probe.ackMs)
    }
}
