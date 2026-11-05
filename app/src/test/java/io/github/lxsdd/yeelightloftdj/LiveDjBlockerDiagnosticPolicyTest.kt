package io.github.lxsdd.yeelightloftdj

import org.junit.Assert.*
import org.junit.Test

class LiveDjBlockerDiagnosticPolicyTest {
    private val healthy = LiveDjLampBlocker("Couchtisch",false,false,false,true)

    @Test fun oldGlobalSessionIsVisibleEvenWithHealthySelectedBulbs() {
        val message = LiveDjBlockerDiagnosticPolicy.explain(
            previousMusicSession=true,activeScene=false,stopPending=false,
            lamps=listOf(healthy,healthy.copy(label="Flur")))
        assertNotNull(message)
        assertTrue(message!!.contains("frühere Musiklicht-Sitzung"))
        assertFalse(message.contains("Couchtisch:"))
    }

    @Test fun quotaOnWellnessMustNotMislabelCouchtisch() {
        val message = LiveDjBlockerDiagnosticPolicy.explain(false,false,false,
            listOf(healthy,healthy.copy(label="Wellness",quotaBlocked=true)))!!
        assertTrue(message.contains("Wellness: Firmware-LAN-Quota"))
        assertFalse(message.contains("Couchtisch:"))
    }

    @Test fun unconfirmedMusicAndUnknownSupportGetDistinctNamedReasons() {
        val msg = LiveDjBlockerDiagnosticPolicy.explain(false,false,false,
            listOf(healthy.copy(musicUnconfirmed=true),
                healthy.copy(label="Unbekannte Lampe",hasMusicCapability=false)))!!
        assertTrue(msg.contains("Couchtisch: music_on=0 nicht bestätigt"))
        assertTrue(msg.contains("Unbekannte Lampe: set_music + set_rgb"))
    }

    @Test fun healthySetHasNoFakeBlocker() {
        assertNull(LiveDjBlockerDiagnosticPolicy.explain(false,false,false,
            listOf(healthy)))
        assertNull(LiveDjBlockerDiagnosticPolicy.explain(false,false,false,
            emptyList()))
    }

    @Test fun residualShowAndStopAreSeparatelyIdentified() {
        val msg = LiveDjBlockerDiagnosticPolicy.explain(false,true,true, listOf(healthy))!!
        assertTrue(msg.contains("Lichtshow/Wiederherstellung"))
        assertTrue(msg.contains("Music-Stopp"))
    }
}
