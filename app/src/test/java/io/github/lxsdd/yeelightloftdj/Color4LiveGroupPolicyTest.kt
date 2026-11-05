package io.github.lxsdd.yeelightloftdj

import org.junit.Assert.*
import org.junit.Test

class Color4LiveGroupPolicyTest {
    @Test fun dynamicBrightnessRequiresAdvertisementOnEveryBulb() {
        val devices = listOf(Color4LiveCandidate("192.168.1.10", "color4", true, false))
        assertTrue(Color4LiveGroupPolicy.refusal(devices, emptySet(), emptySet(), false,
            requireBrightness = true)!!.contains("set_bright"))
        assertNull(Color4LiveGroupPolicy.refusal(devices, emptySet(), emptySet(), false,
            requireBrightness = false))
    }

    private val bulbs=(2..6).map { n ->
        Color4LiveCandidate("192.168.1.$n","yeelink.light.color4",true)
    }
    private fun reject(
        chosen: List<Color4LiveCandidate>,
        blocked: Set<String> = emptySet(),
        pending: Set<String> = emptySet(),
        active: Boolean = false
    ) = Color4LiveGroupPolicy.refusal(chosen,blocked,pending,active)

    @Test fun admitsOneToFourDistinctHealthyBulbs() {
        (1..4).forEach { assertNull(reject(bulbs.take(it))) }
    }
    @Test fun failsOnEmptyOverlimitAndRepeatedAddress() {
        assertNotNull(reject(emptyList()))
        assertNotNull(reject(bulbs))
        assertNotNull(reject(listOf(bulbs[0],bulbs[0])))
    }
    @Test fun rejectsUnknownOrIncapableBulbForEntireGroup() {
        assertNotNull(reject(listOf(bulbs[0],bulbs[1].copy(model="ceiling1"))))
        assertNotNull(reject(listOf(bulbs[0],bulbs[1].copy(music=false))))
    }
    @Test fun respectsPerBulbQuotaPendingStopAndConcurrentWork() {
        assertNotNull(reject(bulbs.take(3),blocked=setOf(bulbs[1].ip)))
        assertNotNull(reject(bulbs.take(3),pending=setOf(bulbs[2].ip)))
        assertNotNull(reject(bulbs.take(2),active=true))
    }
}
