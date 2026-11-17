package io.github.lxsdd.yeelightloftdj

import org.junit.Assert.*
import org.junit.Test

class Color4ChromaOfflineAdmissionTest {
    private val token = "aAbBcCdDeEfF00112233445566778899"
    private fun seen(
        gate: Color4ChromaEvidenceGate, key: String,
        variant: Color4ChromaEvidenceGate.Variant,
    ): Color4ChromaEvidenceGate.Attempt {
        val attempt = gate.beginExplicitOneShot(key, variant)!!
        val replyMethod = when (variant) {
            Color4ChromaEvidenceGate.Variant.V1_UDP_NEW -> "udp_token"
            Color4ChromaEvidenceGate.Variant.V2_UDP_SESS_NEW -> "udp_sess_token"
            Color4ChromaEvidenceGate.Variant.EXPERIMENTAL_CHROMA_METHOD -> "udp_sess_token"
        }
        assertTrue(gate.onTokenReply(attempt, 1, 1, replyMethod, token))
        return attempt
    }

    @Test fun unicastSsdpSilenceDoesNotEraseObservedV2OnExactBulb() {
        val gate = Color4ChromaEvidenceGate()
        val policy = Color4ChromaOfflineAdmission(gate)
        val a = seen(gate, "bulb1", Color4ChromaEvidenceGate.Variant.V2_UDP_SESS_NEW)
        assertFalse(policy.assessV2("bulb1", "2.1.7_0039", false).permitsOfflineV2Preview)
        assertTrue(gate.recordOneColorFrame(a, 2))
        assertTrue(gate.confirmVisibleColorByOperator(a, true))
        assertFalse(policy.assessV2("bulb1", "2.1.7_0039", false).permitsOfflineV2Preview) // still open
        assertTrue(gate.close(a))
        val ok = policy.assessV2("bulb1", "2.1.7_0039", false)
        assertEquals(Color4ChromaOfflineAdmission.Basis.EXACT_DEVICE_V2_HARDWARE_OBSERVED, ok.basis)
        assertTrue(ok.permitsOfflineV2Preview)
        assertFalse(ok.grantsLiveControl)
        assertFalse(ok.demonstratesQuotaBypass)
    }

    @Test fun fourteenBulbsRemainIsolatedAndAdvertisedIsNotHardwareProof() {
        val gate = Color4ChromaEvidenceGate()
        val policy = Color4ChromaOfflineAdmission(gate)
        val a = seen(gate, "d1", Color4ChromaEvidenceGate.Variant.V2_UDP_SESS_NEW)
        assertTrue(gate.recordOneColorFrame(a, 2))
        assertTrue(gate.confirmVisibleColorByOperator(a, true))
        assertTrue(gate.close(a))
        for (i in 2..14) {
            val unverified = policy.assessV2("d$i", "2.1.7_0039", i % 2 == 0)
            assertFalse(unverified.permitsOfflineV2Preview)
            assertFalse(unverified.grantsLiveControl)
            if (i % 2 == 0) assertEquals(
                Color4ChromaOfflineAdmission.Basis.ADVERTISED_NOT_HARDWARE_VERIFIED, unverified.basis
            )
        }
        assertTrue(policy.assessV2("d1", "v2.1.7_0039", false).permitsOfflineV2Preview)
    }

    @Test fun wrongFirmwareAndNoFirmwareDoNotBorrowObservedEvidence() {
        val gate = Color4ChromaEvidenceGate()
        val policy = Color4ChromaOfflineAdmission(gate)
        val a = seen(gate, "d1", Color4ChromaEvidenceGate.Variant.V2_UDP_SESS_NEW)
        assertTrue(gate.recordOneColorFrame(a, 2))
        assertTrue(gate.confirmVisibleColorByOperator(a, true))
        assertTrue(gate.close(a))
        for (fw in listOf<String?>(null, "", "2.1.7_0040", "2.1.7_0039_bogus")) {
            val verdict = policy.assessV2("d1", fw, true)
            assertEquals(Color4ChromaOfflineAdmission.Basis.FIRMWARE_MISMATCH_OR_UNKNOWN, verdict.basis)
            assertFalse(verdict.permitsOfflineV2Preview)
        }
    }

    @Test fun experimentalHandshakeAndV1ColorDoNotMintV2Evidence() {
        for (variant in listOf(Color4ChromaEvidenceGate.Variant.V1_UDP_NEW,
                               Color4ChromaEvidenceGate.Variant.EXPERIMENTAL_CHROMA_METHOD)) {
            val gate = Color4ChromaEvidenceGate()
            val policy = Color4ChromaOfflineAdmission(gate)
            val a = seen(gate, "d1", variant)
            if (variant == Color4ChromaEvidenceGate.Variant.V1_UDP_NEW) {
                assertTrue(gate.recordOneColorFrame(a, 2))
                assertTrue(gate.confirmVisibleColorByOperator(a, true))
            } else assertFalse(gate.recordOneColorFrame(a, 2))
            assertTrue(gate.close(a))
            assertFalse(policy.assessV2("d1", "2.1.7_0039", false).permitsOfflineV2Preview)
        }
    }

    @Test fun correlatedErrorAndOperatorResetImmediatelyRevokeEvidence() {
        val gate = Color4ChromaEvidenceGate()
        val policy = Color4ChromaOfflineAdmission(gate)
        val a = seen(gate, "d1", Color4ChromaEvidenceGate.Variant.V2_UDP_SESS_NEW)
        assertTrue(gate.recordOneColorFrame(a, 2))
        assertTrue(gate.confirmVisibleColorByOperator(a, true))
        assertTrue(gate.close(a))
        assertTrue(policy.assessV2("d1", "2.1.7_0039", false).permitsOfflineV2Preview)
        assertTrue(gate.onCorrelatedDeviceError(a, 2))
        assertFalse(policy.assessV2("d1", "2.1.7_0039", false).permitsOfflineV2Preview)
        assertTrue(gate.clearOnlyByOperator("d1"))
        assertFalse(policy.assessV2("d1", "2.1.7_0039", false).permitsOfflineV2Preview)
        assertFalse(gate.onCorrelatedDeviceError(a, 2))
    }

    @Test fun closedGenerationIsolationUnderFiveThousandReadEvents() {
        val gate = Color4ChromaEvidenceGate()
        val policy = Color4ChromaOfflineAdmission(gate)
        val a = seen(gate, "d1", Color4ChromaEvidenceGate.Variant.V2_UDP_SESS_NEW)
        assertTrue(gate.recordOneColorFrame(a, 2))
        assertTrue(gate.confirmVisibleColorByOperator(a, true))
        assertTrue(gate.close(a))
        repeat(5000) {
            assertTrue(policy.assessV2("d1", "2.1.7_0039", false).permitsOfflineV2Preview)
            assertFalse(policy.assessV2("d2", "2.1.7_0039", true).permitsOfflineV2Preview)
        }
        assertFalse(gate.beginExplicitOneShot("d1", Color4ChromaEvidenceGate.Variant.V2_UDP_SESS_NEW) != null)
    }
}
