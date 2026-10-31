package io.github.lxsdd.yeelightloftdj

import org.junit.Assert.*
import org.junit.Test

class LampOperationGateTest {
    @Test fun idleLampCanBeIdentified() {
        assertTrue(LampOperationGate.canIdentify(false, null, emptySet(), emptySet(), false))
    }

    @Test fun activeSceneOrBusyOperationPreventsIdentification() {
        assertFalse(LampOperationGate.canIdentify(false, "chase", emptySet(), emptySet(), false))
        assertFalse(LampOperationGate.canIdentify(true, null, emptySet(), emptySet(), false))
    }

    @Test fun stoppedAppWithPersistedIpStillRequiresRecovery() {
        assertTrue(LampOperationGate.needsRecovery(null, setOf("192.168.1.20"), emptySet(), false))
        assertFalse(LampOperationGate.canIdentify(false, null, setOf("192.168.1.20"), emptySet(), false))
    }

    @Test fun recoveryJournalWithoutRunningIpIsNotLost() {
        val journal = setOf("192.168.1.21")
        assertTrue(LampOperationGate.needsRecovery(null, emptySet(), journal, false))
        assertEquals(journal, LampOperationGate.pendingIps(emptySet(), journal))
        assertFalse(LampOperationGate.canIdentify(false, null, emptySet(), journal, false))
    }

    @Test fun pendingIpsAreDeduplicatedForRecoveryRetry() {
        val tracked = setOf("192.168.1.20", "192.168.1.21")
        val journal = setOf("192.168.1.21", "192.168.1.22")
        assertEquals(3, LampOperationGate.pendingIps(tracked, journal).size)
    }

    @Test fun interruptedMusicSessionCannotUnlockIdentification() {
        assertTrue(LampOperationGate.needsRecovery(null, emptySet(), emptySet(), true))
        assertFalse(LampOperationGate.canIdentify(false, null, emptySet(), emptySet(), true))
    }

    @Test fun confirmedRecoveryUnblocksIdentifying() {
        assertFalse(LampOperationGate.canIdentify(false, null,
            setOf("192.168.1.20"), emptySet(), false))
        assertTrue(LampOperationGate.canIdentify(false, null, emptySet(), emptySet(), false))
    }
}
