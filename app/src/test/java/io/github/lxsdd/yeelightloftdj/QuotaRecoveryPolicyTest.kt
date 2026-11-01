package io.github.lxsdd.yeelightloftdj

import org.junit.Assert.*
import org.junit.Test

class QuotaRecoveryPolicyTest {
    @Test fun recognizesExactDeviceRejectionWithoutMisclassifyingTimeout() {
        assertTrue(QuotaRecoveryPolicy.isQuotaRejection("stop_cf abgelehnt: client quota exceeded"))
        assertTrue(QuotaRecoveryPolicy.isQuotaRejection("CLIENT QUOTA EXCEEDED"))
        assertFalse(QuotaRecoveryPolicy.isQuotaRejection("SocketTimeoutException"))
        assertFalse(QuotaRecoveryPolicy.isQuotaRejection(null))
    }

    @Test fun throttlesRepeatedAttemptsButDoesNotClearRecovery() {
        val first = 10_000L
        assertFalse(QuotaRecoveryPolicy.canRetry(first + 1_000, first))
        assertEquals(59_000L, QuotaRecoveryPolicy.remainingMs(first + 1_000, first))
        assertTrue(QuotaRecoveryPolicy.canRetry(first + 60_000, first))
        assertEquals(0L, QuotaRecoveryPolicy.remainingMs(first + 61_000, first))
    }

    @Test fun noRejectionMeansNoCooldown() {
        assertTrue(QuotaRecoveryPolicy.canRetry(100L, null))
    }
}
