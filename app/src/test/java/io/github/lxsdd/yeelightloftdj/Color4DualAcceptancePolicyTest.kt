package io.github.lxsdd.yeelightloftdj

import org.junit.Assert.*
import org.junit.Test

class Color4DualAcceptancePolicyTest {
    private fun lamp(ip: String, model: String = "color4",
        methods: Set<String> = setOf("set_music","set_rgb")) =
        YeelightDevice(id=ip, ip=ip, model=model, methods=methods)
    private val two = listOf(lamp("192.168.178.21"),lamp("192.168.178.22"))

    @Test fun signedAuthorizationIsUnavailableInThisSourceRevision() {
        assertFalse(Color4DualAcceptancePolicy.LIVE_TEST_APPROVED)
        assertTrue(Color4DualAcceptancePolicy.refusal(two,emptySet(),false)!!
            .contains("gesperrt"))
        assertNull(Color4DualAcceptancePolicy.refusal(two,emptySet(),false,
            signedTestApproved=true))
    }
    @Test fun rejectsAllInvalidSelectionsAndQuarantinedPair() {
        assertNotNull(Color4DualAcceptancePolicy.refusal(emptyList(),emptySet(),false,true))
        assertNotNull(Color4DualAcceptancePolicy.refusal(two.take(1),emptySet(),false,true))
        assertNotNull(Color4DualAcceptancePolicy.refusal(two+lamp("192.168.178.23"),
            emptySet(),false,true))
        assertNotNull(Color4DualAcceptancePolicy.refusal(
            listOf(two[0],two[0].copy(id="duplicate")),emptySet(),false,true))
        assertNotNull(Color4DualAcceptancePolicy.refusal(two,setOf(two[1].ip),false,true))
        assertNotNull(Color4DualAcceptancePolicy.refusal(two,emptySet(),true,true))
        assertNotNull(Color4DualAcceptancePolicy.refusal(
            listOf(two[0],lamp(two[1].ip,"unknown")),emptySet(),false,true))
        assertNotNull(Color4DualAcceptancePolicy.refusal(
            listOf(two[0],lamp(two[1].ip,methods=setOf("set_music"))),
            emptySet(),false,true))
    }
    @Test fun combinedBudgetIntervalsAndBothActiveSocketsAreMandatory() {
        assertFalse(Color4DualAcceptancePolicy.permitsFrame(0,1000,0,2,1))
        assertFalse(Color4DualAcceptancePolicy.permitsFrame(0,1000,0,1,1))
        assertFalse(Color4DualAcceptancePolicy.permitsFrame(0,1000,0,3,3))
        assertTrue(Color4DualAcceptancePolicy.permitsFrame(0,500,0,2,2))
        assertFalse(Color4DualAcceptancePolicy.permitsFrame(500,999,2,2,2))
        assertTrue(Color4DualAcceptancePolicy.permitsFrame(500,1000,2,2,2))
        assertTrue(Color4DualAcceptancePolicy.permitsFrame(0,500,38,2,2))
        assertFalse(Color4DualAcceptancePolicy.permitsFrame(0,500,39,2,2))
        assertFalse(Color4DualAcceptancePolicy.permitsFrame(0,500,40,2,2))
        assertFalse(Color4DualAcceptancePolicy.permitsFrame(1500,1000,0,2,2))
        assertEquals(10_000L,Color4DualAcceptancePolicy.DURATION_MS)
        assertEquals(40,Color4DualAcceptancePolicy.MAX_TOTAL_RGB_WRITES)
    }
    @Test fun independentPerDeviceDiagnosticsNeverClaimOpticalAck() {
        val line=Color4DualAcceptancePolicy.perDeviceReport(
            listOf("192.168.178.21","192.168.178.22"),
            mapOf("192.168.178.21" to 9L,"192.168.178.22" to 9L),
            mapOf("192.168.178.22" to 1L))
        assertTrue(line.contains("...21 RGB 9, Fehler 0"))
        assertTrue(line.contains("...22 RGB 9, Fehler 1"))
    }
}
