package io.github.lxsdd.yeelightloftdj

import org.junit.Assert.*
import org.junit.Test

class MusicStopSafetyTest {
    @Test fun firmwareRejectionOfStopIsDurablyProtectedFromReplay() {
        assertTrue(MusicStopSafety.unconfirmed(
            "Music-Mode-Start gescheitert und einmaliges set_music(0) nicht bestätigt"))
        assertTrue(MusicStopSafety.unconfirmed(
            "set_music(0) abgelehnt (Fehlercode -5001): invalid params"))
        val persisted = setOf("192.168.0.134")
        assertFalse(MusicStopSafety.permitsStop("192.168.0.134", persisted))
        assertTrue(MusicStopSafety.permitsStop("192.168.0.141", persisted))
        assertTrue(MusicStopSafety.permitsStop("192.168.0.134",
            persisted - "192.168.0.134"))
    }

    @Test fun unrelatedErrorsDoNotClaimFailedMusicStop() {
        assertFalse(MusicStopSafety.unconfirmed(null))
        assertFalse(MusicStopSafety.unconfirmed(
            "set_music(1) abgelehnt (Fehlercode -5001): invalid params"))
        assertFalse(MusicStopSafety.unconfirmed(
            "set_music(1) wurde ausdrücklich abgelehnt; kein automatischer Deaktivierungsbefehl gesendet"))
        assertFalse(MusicStopSafety.unconfirmed("set_rgb: invalid params"))
        assertFalse(MusicStopSafety.unconfirmed("client quota exceeded"))
    }
}
