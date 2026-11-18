package io.github.lxsdd.yeelightloftdj

import org.junit.Assert.*
import org.junit.Test

class LoftGroupAndStartTest {
    private val dining = (0 until 3).map {
        YeelightDevice("dining-$it", "192.168.1.${20 + it}",
            model = "color4", methods = setOf("start_cf", "stop_cf", "set_scene", "set_rgb"))
    }
    private val white = YeelightDevice("white", "192.168.1.29",
        model = "lamp", methods = setOf("set_bright"))

    @Test fun quarantinedColor4NeverEntersFlowOrRestoreTargets() {
        val wellness = dining[1]
        val result = SceneTargetPolicy.flowTargets(dining, setOf(wellness.ip))
        assertEquals(dining.filterNot { it.ip == wellness.ip }, result.supported)
        assertEquals(listOf(wellness), result.blocked)
        assertTrue(result.unsupported.isEmpty())
    }

    @Test fun duplicateQuarantinedAliasesNeverEnterSceneTwice() {
        val wellness = dining[1]
        val aliases = dining + wellness.copy(id = "manual-Wellness")
        val result = SceneTargetPolicy.flowTargets(aliases, setOf(wellness.ip))
        assertEquals(2, result.supported.size)
        assertEquals(1, result.blocked.size)
        assertEquals(wellness.ip, result.blocked.single().ip)
    }

    @Test fun blockedAndUnsupportedCategoriesRemainSeparate() {
        val blocked = dining[0]
        val result = SceneTargetPolicy.flowTargets(dining + white, setOf(blocked.ip))
        assertEquals(2, result.supported.size)
        assertEquals(listOf(blocked), result.blocked)
        assertEquals(listOf(white), result.unsupported)
    }

    @Test fun allQuarantinedDoesNotYieldAnyRunnableBulb() {
        val result = SceneTargetPolicy.flowTargets(dining, dining.map { it.ip }.toSet())
        assertTrue(result.supported.isEmpty())
        assertEquals(3, result.blocked.size)
    }

    @Test fun threeDiningLampsRemainStartable() {
        val targets = SceneTargetPolicy.flowTargets(dining)
        assertEquals(3, targets.supported.size)
        assertTrue(targets.unsupported.isEmpty())
    }

    @Test fun fifteenColor4LampsRemainSelectableWithoutOneGlobalGate() {
        val many = (0 until 15).map {
            YeelightDevice("color-$it", "192.168.1.${30 + it}",
                model = "color4", methods = setOf("start_cf", "stop_cf", "set_rgb"))
        }
        val targets = SceneTargetPolicy.flowTargets(many)
        assertEquals(15, targets.supported.size)
        assertTrue(targets.unsupported.isEmpty())
    }

    @Test fun mixedGroupSkipsKnownUnsupportedAndDeduplicatesIps() {
        val input = dining + white + dining[0].copy(id = "duplicate-id")
        val targets = SceneTargetPolicy.flowTargets(input)
        assertEquals(3, targets.supported.size)
        assertEquals(listOf(white), targets.unsupported)
    }

    @Test fun loftDefaultsToThreeRoomsButCanBeExplicitlyEdited() {
        val devices = dining + white
        val rooms = mapOf("dining-0" to "Esszimmer",
            "dining-1" to "Wohnzimmer",
            "dining-2" to "Hobbyraum",
            "white" to "Schlafzimmer")
        assertEquals(dining.map { it.id }.toSet(),
            SceneTargetPolicy.loftIds(devices, rooms, null))
        assertEquals(setOf("white", "dining-0"),
            SceneTargetPolicy.loftIds(devices, rooms, setOf("white", "dining-0")))
        assertEquals(emptySet<String>(),
            SceneTargetPolicy.loftIds(devices, rooms, emptySet()))
    }

    @Test fun fifteenLampFlowHasDistinctPositionsAndValidFrames() {
        val conf = ChaseConfig(style = ChaseStyle.RAINBOW_TAIL, tailLength = 4)
        val all = (0 until 15).map { ChaseCompiler.frames(conf, it, 15) }
        assertEquals(15, all.size)
        assertTrue(all.all { it.size == 15 })
        for (step in 0 until 15) {
            assertEquals(1, all.count { it[step].brightness == conf.brightness })
        }
    }
}
