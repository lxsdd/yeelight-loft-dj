package io.github.lxsdd.yeelightloftdj

import org.junit.Assert.*
import org.junit.Test

class KnightRiderPlanTest {
    @Test fun classicScannerOnlyOneLampPerStepAndReversesAtEdges() {
        assertEquals(listOf(0,1,2,3,4,3,2,1),
            KnightRiderPlan.path(5, false, true))
        assertEquals(listOf(4,3,2,1,0,1,2,3),
            KnightRiderPlan.path(5, true, true))
        assertEquals(listOf(0,1), KnightRiderPlan.path(2, false, true))
        assertEquals(listOf(0,1,2), KnightRiderPlan.path(3, false, false))
    }

    @Test fun localRateLimitProtectsSmallGroupsEspeciallyCenterInBounce() {
        assertTrue(KnightRiderPlan.minimumStepMs(3, true) >= 1600)
        assertTrue(KnightRiderPlan.minimumStepMs(2, true) >= 1500)
        assertTrue(KnightRiderPlan.minimumStepMs(15, true) < 700)
        assertEquals(KnightRiderPlan.minimumStepMs(3,true),
            KnightRiderPlan.effectiveStepMs(180,3,true))
        assertEquals(4000, KnightRiderPlan.effectiveStepMs(4000,15,true))
        for (n in 2..24) {
            for (bounce in listOf(false,true)) {
                val cycle = KnightRiderPlan.path(n,false,bounce)
                val worst = (0 until n).maxOf { i -> cycle.count { it == i } }
                val rate = (60_000.0 * worst * 3) /
                    (KnightRiderPlan.effectiveStepMs(180,n,bounce) * cycle.size)
                assertTrue("n=$n,bounce=$bounce,rate=$rate",rate <= 55.1)
            }
        }
    }

    @Test fun onlyConfirmedRgbSceneAndReadablePowerQualify() {
        val valid = YeelightDevice("c","192.168.0.11",model="color4",
            methods=setOf("set_scene","set_power","get_prop","set_rgb"))
        val unknown = valid.copy(methods=emptySet())
        val mono = valid.copy(methods=setOf("set_power","get_prop","set_bright"))
        assertTrue(KnightRiderPlan.scannerCompatible(valid))
        assertFalse(KnightRiderPlan.scannerCompatible(unknown))
        assertFalse(KnightRiderPlan.scannerCompatible(mono))
    }

    @Test fun colorCycle() {
        assertEquals(0xFF0000, KnightRiderPlan.stepColor(listOf(0xFF0000,0x00FF00),0))
        assertEquals(0x00FF00, KnightRiderPlan.stepColor(listOf(0xFF0000,0x00FF00),3))
    }
}
