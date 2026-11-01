package io.github.lxsdd.yeelightloftdj

import org.junit.Assert.*
import org.junit.Test

class NamedLampGroupsTest {
    @Test fun acceptsNamesButNotInvalidOrDuplicateDelimiter() {
        assertTrue(NamedLampGroups.validName("Esstisch und Küche"))
        assertFalse(NamedLampGroups.validName(" "))
        assertFalse(NamedLampGroups.validName("Gruppe:1"))
        assertFalse(NamedLampGroups.validName("x".repeat(41)))
    }

    @Test fun rediscoveryPreservesMembershipWithoutDuplicatingBulbs() {
        val groups = mapOf("Esstisch" to setOf("ip-10", "ip-11"),
            "Party" to setOf("ip-10"))
        val migrated = NamedLampGroups.migrateId(groups, "ip-10", "0x01")
        assertEquals(setOf("0x01", "ip-11"), migrated["Esstisch"])
        assertEquals(setOf("0x01"), migrated["Party"])
        assertEquals(setOf("ip-11"),
            NamedLampGroups.removeId(migrated, "0x01")["Esstisch"])
    }
}
