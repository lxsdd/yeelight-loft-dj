package io.github.lxsdd.yeelightloftdj

import org.junit.Assert.*
import org.junit.Test

class Color4PairReadinessPolicyTest {
    private fun lamp(ip:String, model:String="color4",
                     methods:Set<String> = setOf("set_music","set_rgb")) =
        YeelightDevice(id=ip, ip=ip, model=model, methods=methods)

    @Test fun assessmentSeparatesCapabilityPassFromLightTestAndFailures() {
        val valid = listOf(lamp("10.0.0.1"), lamp("10.0.0.2"))
        val passed = Color4PairReadinessPolicy.assess(valid, emptySet(), false)
        assertTrue(passed.eligible)
        assertTrue(passed.explanation.contains("set_music"))
        val blocked = Color4PairReadinessPolicy.assess(valid, setOf("10.0.0.2"), false)
        assertFalse(blocked.eligible)
        assertTrue(blocked.explanation.contains("Quarantäne"))
        val empty = Color4PairReadinessPolicy.assess(emptyList(), emptySet(), false)
        assertFalse(empty.eligible)
    }

    @Test fun exactlyTwoDistinctCapableColor4AreOnlyReadinessCandidates() {
        val pair=listOf(lamp("10.0.0.1"),lamp("10.0.0.2"))
        assertNull(Color4PairReadinessPolicy.refusal(pair, emptySet(),false))
        assertNotNull(Color4PairReadinessPolicy.refusal(listOf(pair.first()),emptySet(),false))
        assertNotNull(Color4PairReadinessPolicy.refusal(pair+lamp("10.0.0.3"),emptySet(),false))
        assertNotNull(Color4PairReadinessPolicy.refusal(
            listOf(pair[0],pair[0].copy(id="another")),emptySet(),false))
    }

    @Test fun blocksQuarantineRecoveryAndMissingCapabilities() {
        val pair=listOf(lamp("10.0.0.1"),lamp("10.0.0.2"))
        assertTrue(Color4PairReadinessPolicy.refusal(pair,setOf("10.0.0.2"),false)!!
            .contains("Quarantäne"))
        assertTrue(Color4PairReadinessPolicy.refusal(pair,emptySet(),true)!!
            .contains("Wiederherstellung"))
        assertNotNull(Color4PairReadinessPolicy.refusal(
            listOf(pair.first(),lamp("10.0.0.2",model="unknown")),emptySet(),false))
        assertNotNull(Color4PairReadinessPolicy.refusal(
            listOf(pair.first(),lamp("10.0.0.2",methods=setOf("set_rgb"))),
            emptySet(),false))
    }
}
