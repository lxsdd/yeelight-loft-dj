package io.github.lxsdd.yeelightloftdj

import org.junit.Assert.*
import org.junit.Test

class TargetReferenceTest {
    @Test fun manualToSsdpIdMigrationPreservesSelectedSingleLamp() {
        assertEquals("Lampe:0x20",
            TargetReference.deviceRenamed("Lampe:manual-192.168.1.20",
                "manual-192.168.1.20", "0x20"))
        assertEquals("Loft",
            TargetReference.deviceRenamed("Loft", "manual-a", "0x20"))
    }

    @Test fun roomRenameAndDeletionNeverLeaveInvisibleTarget() {
        assertEquals("Raum:Esstisch",
            TargetReference.roomRenamed("Raum:Esszimmer", "Esszimmer", "Esstisch"))
        assertEquals("Auswahl",
            TargetReference.roomRemoved("Raum:Esstisch", "Esstisch"))
        assertEquals("Loft", TargetReference.roomRemoved("Loft", "Esstisch"))
    }

    @Test fun forgottenSingleLampFallsBackToSafeExplicitTestTarget() {
        assertEquals("Auswahl", TargetReference.deviceRemoved("Lampe:lost", "lost"))
        assertEquals("Auswahl", TargetReference.deviceRemoved("Auswahl", "lost"))
    }
}
