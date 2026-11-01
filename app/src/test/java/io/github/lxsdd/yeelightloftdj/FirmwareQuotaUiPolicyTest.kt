package io.github.lxsdd.yeelightloftdj

import org.junit.Assert.*
import org.junit.Test

class FirmwareQuotaUiPolicyTest {
    @Test fun firmwareErrorIdentifiesOneTargetAndDoesNotBlockOthers() {
        val message=FirmwareQuotaUiPolicy.failedPreflight("Esstisch 2","192.168.178.117")
        assertTrue(message.contains("Esstisch 2"))
        assertTrue(message.contains("...117"))
        assertTrue(message.contains("client quota exceeded"))
        assertTrue(message.contains("andere Lampen"))
        assertFalse(message.contains("Alle Lampen gesperrt"))
    }
    @Test fun selectionChangeRefreshesStaleErrorWithoutClearingQuarantine() {
        val blocked=listOf("Esstisch 2 (...117)")
        val next=FirmwareQuotaUiPolicy.changedSelection("Eigene Lampenauswahl",1,blocked)
        assertTrue(next.contains("1 Lampen"))
        assertTrue(next.contains("LAN-geschützt"))
        assertFalse(next.contains("Music-Preflight abgelehnt"))
        assertTrue(FirmwareQuotaUiPolicy.quarantinedSummary(blocked)
            .contains("Esstisch 2 (...117)"))
        assertTrue(FirmwareQuotaUiPolicy.quarantinedSummary(blocked)
            .contains("Andere Geräte bleiben bedienbar"))
    }
    @Test fun emptySelectionAndNoQuarantineAreStillUnderstandable() {
        assertTrue(FirmwareQuotaUiPolicy.changedSelection("Eigene Lampenauswahl",0,emptyList())
            .contains("0 Lampen"))
        assertEquals("",FirmwareQuotaUiPolicy.quarantinedSummary(emptyList()))
    }
    @Test fun manyBlockedBulbsLimitOutputWithoutHidingCount() {
        val six=(1..6).map { "Lampe " + it }
        val message=FirmwareQuotaUiPolicy.quarantinedSummary(six)
        assertTrue(message.contains("+2"))
        assertFalse(message.contains("Lampe 6"))
    }
}
