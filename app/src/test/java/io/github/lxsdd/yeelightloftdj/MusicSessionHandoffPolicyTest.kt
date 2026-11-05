package io.github.lxsdd.yeelightloftdj

import org.junit.Assert.*
import org.junit.Test

class MusicSessionHandoffPolicyTest {
    @Test fun explicitPreflightNeverStopsWhenMusicAlreadyOffOrUnknown() {
        assertEquals(MusicHandoffReadDecision.ALREADY_STOPPED,
            MusicSessionHandoffPolicy.decidePreflight(false))
        assertEquals(MusicHandoffReadDecision.UNKNOWN,
            MusicSessionHandoffPolicy.decidePreflight(null))
        assertEquals(MusicHandoffReadDecision.END_ONCE,
            MusicSessionHandoffPolicy.decidePreflight(true))
    }

    @Test fun activeReverseChannelQuotaOrUnknownSupportCannotBeTakenOver() {
        assertTrue(MusicSessionHandoffPolicy.canOffer(true,false,false,false))
        assertFalse(MusicSessionHandoffPolicy.canOffer(false,false,false,false))
        assertFalse(MusicSessionHandoffPolicy.canOffer(true,true,false,false))
        assertFalse(MusicSessionHandoffPolicy.canOffer(true,false,true,false))
        assertFalse(MusicSessionHandoffPolicy.canOffer(true,false,false,true))
    }

    @Test fun ackWithoutReadbackDoesNotClearRecovery() {
        assertFalse(MusicSessionHandoffPolicy.verifiedStopped(false,false))
        assertFalse(MusicSessionHandoffPolicy.verifiedStopped(true,true))
        assertFalse(MusicSessionHandoffPolicy.verifiedStopped(true,null))
        assertTrue(MusicSessionHandoffPolicy.verifiedStopped(true,false))
    }
}
