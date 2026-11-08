package io.github.lxsdd.yeelightloftdj

import org.junit.Assert.*
import org.junit.Test

class Color4ChromaEvidenceGateTest {
    private val token = "aAbBcCdDeEfF00112233445566778899"

    @Test fun option4V2RequiresRealVisibleConfirmation() {
        val g = Color4ChromaEvidenceGate()
        val t = g.beginExplicitOneShot("lamp-a", Color4ChromaEvidenceGate.Variant.V2_UDP_SESS_NEW)!!
        assertFalse(g.recordOneColorFrame(t, 2))
        assertTrue(g.onTokenReply(t, 1, 1, "udp_sess_token", token))
        assertEquals(Color4ChromaEvidenceGate.Evidence.HANDSHAKE_ONLY, g.evidence("lamp-a"))
        assertTrue(g.recordOneColorFrame(t, 2))
        assertEquals(Color4ChromaEvidenceGate.Evidence.COLOR_SENT_UNCONFIRMED, g.evidence("lamp-a"))
        assertFalse(g.confirmVisibleColorByOperator(t, false))
        assertTrue(g.confirmVisibleColorByOperator(t, true))
        assertTrue(g.close(t))
        assertEquals(Color4ChromaEvidenceGate.Evidence.COLOR_VISUALLY_CONFIRMED, g.evidence("lamp-a"))
    }

    @Test fun experimentalChromaMethodNeverQualifiesColor() {
        val g = Color4ChromaEvidenceGate()
        val t = g.beginExplicitOneShot("lamp-a", Color4ChromaEvidenceGate.Variant.EXPERIMENTAL_CHROMA_METHOD)!!
        assertTrue(g.onTokenReply(t, 1, 1, "udp_sess_token", token))
        assertFalse(g.recordOneColorFrame(t, 2))
        assertFalse(g.confirmVisibleColorByOperator(t, true))
        assertTrue(g.close(t))
        assertEquals(Color4ChromaEvidenceGate.Evidence.EXPERIMENTAL_METHOD_ONLY, g.evidence("lamp-a"))
    }

    @Test fun tokenWrongIdIsIgnoredButCorrelatedWrongMethodFailsClosed() {
        val g = Color4ChromaEvidenceGate()
        val t = g.beginExplicitOneShot("lamp-a", Color4ChromaEvidenceGate.Variant.V1_UDP_NEW)!!
        assertFalse(g.onTokenReply(t, 1, 2, "udp_token", token))
        assertFalse(g.onTokenReply(t, 2, 2, "udp_token", token))
        assertEquals(Color4ChromaEvidenceGate.Evidence.AWAITING_HANDSHAKE, g.evidence("lamp-a"))
        assertFalse(g.onTokenReply(t, 1, 1, "udp_sess_token", token))
        assertEquals(Color4ChromaEvidenceGate.Evidence.REJECTED_OR_UNCERTAIN, g.evidence("lamp-a"))
        assertFalse(g.onTokenReply(t, 1, 1, "udp_token", token)) // cannot replay after fail-closed
        assertNull(g.beginExplicitOneShot("lamp-a", Color4ChromaEvidenceGate.Variant.V1_UDP_NEW))
        assertTrue(g.clearOnlyByOperator("lamp-a"))
        val retry = g.beginExplicitOneShot("lamp-a", Color4ChromaEvidenceGate.Variant.V1_UDP_NEW)!!
        assertTrue(g.onTokenReply(retry, 1, 1, "udp_token", token))
        assertFalse(g.onTokenReply(retry, 1, 1, "udp_token", token))
    }

    @Test fun invalidCorrelatedTokenCannotBeReplacedByLaterValidReplay() {
        val g = Color4ChromaEvidenceGate()
        val t = g.beginExplicitOneShot("lamp-a", Color4ChromaEvidenceGate.Variant.V2_UDP_SESS_NEW)!!
        assertFalse(g.onTokenReply(t, 1, 1, "udp_sess_token", token.dropLast(1)))
        assertFalse(g.onTokenReply(t, 1, 1, "udp_sess_token", token))
        assertFalse(g.recordOneColorFrame(t, 2))
        assertFalse(g.confirmVisibleColorByOperator(t, true))
        assertFalse(g.close(t))
        assertEquals(Color4ChromaEvidenceGate.Evidence.REJECTED_OR_UNCERTAIN, g.evidence("lamp-a"))
        assertNull(g.confirmedClosedVariant("lamp-a"))
        assertTrue(g.clearOnlyByOperator("lamp-a"))
        assertNull(g.confirmedClosedVariant("lamp-a"))
    }

    @Test fun oneFrameOnlyAndNoAutomaticRepeatAfterClose() {
        val g = Color4ChromaEvidenceGate()
        val t = g.beginExplicitOneShot("lamp-a", Color4ChromaEvidenceGate.Variant.V2_UDP_SESS_NEW)!!
        assertTrue(g.onTokenReply(t, 1, 1, "udp_sess_token", token))
        assertFalse(g.recordOneColorFrame(t, 3))
        assertTrue(g.recordOneColorFrame(t, 2))
        assertFalse(g.recordOneColorFrame(t, 2))
        assertTrue(g.close(t))
        assertEquals(Color4ChromaEvidenceGate.Evidence.REJECTED_OR_UNCERTAIN, g.evidence("lamp-a"))
        assertNull(g.beginExplicitOneShot("lamp-a", Color4ChromaEvidenceGate.Variant.V2_UDP_SESS_NEW))
        assertTrue(g.clearOnlyByOperator("lamp-a"))
        assertNotNull(g.beginExplicitOneShot("lamp-a", Color4ChromaEvidenceGate.Variant.V2_UDP_SESS_NEW))
    }

    @Test fun correlatedLateFrameRejectionInvalidatesPreviousVisualPass() {
        val g = Color4ChromaEvidenceGate()
        val t = g.beginExplicitOneShot("lamp-a", Color4ChromaEvidenceGate.Variant.V2_UDP_SESS_NEW)!!
        assertTrue(g.onTokenReply(t, 1, 1, "udp_sess_token", token))
        assertTrue(g.recordOneColorFrame(t, 2))
        assertTrue(g.confirmVisibleColorByOperator(t, true))
        assertFalse(g.onCorrelatedDeviceError(t, 3))
        assertTrue(g.onCorrelatedDeviceError(t, 2))
        assertEquals(Color4ChromaEvidenceGate.Evidence.REJECTED_OR_UNCERTAIN, g.evidence("lamp-a"))
        assertFalse(g.close(t))
    }

    @Test fun wrongGenerationAndUnknownBulbNeverAdvance() {
        val g = Color4ChromaEvidenceGate()
        val t = g.beginExplicitOneShot("a", Color4ChromaEvidenceGate.Variant.V2_UDP_SESS_NEW)!!
        val fake = Color4ChromaEvidenceGate.Attempt(t.deviceKey, t.generation + 1, t.variant)
        assertFalse(g.onTokenReply(fake, 1, 1, "udp_sess_token", token))
        assertFalse(g.onTokenReply(Color4ChromaEvidenceGate.Attempt("b", t.generation, t.variant), 1, 1, "udp_sess_token", token))
        assertFalse(g.confirmVisibleColorByOperator(fake, true))
        assertEquals(Color4ChromaEvidenceGate.Evidence.UNTESTED, g.evidence("b"))
        assertEquals(Color4ChromaEvidenceGate.Evidence.AWAITING_HANDSHAKE, g.evidence("a"))
    }

    @Test fun forgedMatchingTicketCannotMintOrRevokeHardwareProof() {
        val g = Color4ChromaEvidenceGate()
        val real = g.beginExplicitOneShot("bulb", Color4ChromaEvidenceGate.Variant.V2_UDP_SESS_NEW)!!
        // A caller in the same Kotlin module can instantiate an identical public
        // ticket without owning the object actually issued by the gate.
        val counterfeit = Color4ChromaEvidenceGate.Attempt(real.deviceKey, real.generation, real.variant)
        assertFalse(g.onTokenReply(counterfeit, 1, 1, "udp_sess_token", token))
        assertFalse(g.recordOneColorFrame(counterfeit, 2))
        assertFalse(g.confirmVisibleColorByOperator(counterfeit, true))
        assertFalse(g.onCorrelatedDeviceError(counterfeit, 1))
        assertFalse(g.onAmbiguousSendOrTimeout(counterfeit, 1))
        assertFalse(g.close(counterfeit))
        assertEquals(Color4ChromaEvidenceGate.Evidence.AWAITING_HANDSHAKE, g.evidence("bulb"))
        assertTrue(g.onTokenReply(real, 1, 1, "udp_sess_token", token))
        assertTrue(g.recordOneColorFrame(real, 2))
        assertTrue(g.confirmVisibleColorByOperator(real, true))
        assertTrue(g.close(real))
        assertEquals(Color4ChromaEvidenceGate.Variant.V2_UDP_SESS_NEW, g.confirmedClosedVariant("bulb"))
        assertFalse(g.onCorrelatedDeviceError(counterfeit, 2))
        assertEquals(Color4ChromaEvidenceGate.Variant.V2_UDP_SESS_NEW, g.confirmedClosedVariant("bulb"))
    }

    @Test fun manyCounterfeitTicketsDoNotCauseCrossDeviceOrGenerationReplay() {
        val g = Color4ChromaEvidenceGate()
        val a = g.beginExplicitOneShot("a", Color4ChromaEvidenceGate.Variant.V1_UDP_NEW)!!
        val b = g.beginExplicitOneShot("b", Color4ChromaEvidenceGate.Variant.V2_UDP_SESS_NEW)!!
        repeat(5000) {
            val fakeA = Color4ChromaEvidenceGate.Attempt(a.deviceKey, a.generation, a.variant)
            val fakeB = Color4ChromaEvidenceGate.Attempt(b.deviceKey, b.generation, b.variant)
            assertFalse(g.onTokenReply(fakeA, 1, 1, "udp_token", token))
            assertFalse(g.onTokenReply(fakeB, 1, 1, "udp_sess_token", token))
            assertFalse(g.onCorrelatedDeviceError(fakeA, 1))
        }
        assertTrue(g.onTokenReply(a, 1, 1, "udp_token", token))
        assertTrue(g.onTokenReply(b, 1, 1, "udp_sess_token", token))
        assertTrue(g.close(a))
        assertTrue(g.clearOnlyByOperator("a"))
        val newer = g.beginExplicitOneShot("a", Color4ChromaEvidenceGate.Variant.V1_UDP_NEW)!!
        assertFalse(g.onCorrelatedDeviceError(a, 1))
        assertFalse(g.onCorrelatedDeviceError(Color4ChromaEvidenceGate.Attempt(newer.deviceKey, newer.generation, newer.variant), 1))
        assertEquals(Color4ChromaEvidenceGate.Evidence.AWAITING_HANDSHAKE, g.evidence("a"))
    }

    @Test fun attemptFromDifferentGateWithIdenticalSequenceIsNotAccepted() {
        val firstGate = Color4ChromaEvidenceGate()
        val otherGate = Color4ChromaEvidenceGate()
        val a = firstGate.beginExplicitOneShot("bulb", Color4ChromaEvidenceGate.Variant.V2_UDP_SESS_NEW)!!
        val foreign = otherGate.beginExplicitOneShot("bulb", Color4ChromaEvidenceGate.Variant.V2_UDP_SESS_NEW)!!
        assertEquals(a.generation, foreign.generation)
        assertEquals(a.deviceKey, foreign.deviceKey)
        assertEquals(a.variant, foreign.variant)
        assertFalse(firstGate.onTokenReply(foreign, 1, 1, "udp_sess_token", token))
        assertFalse(firstGate.close(foreign))
        assertFalse(firstGate.onCorrelatedDeviceError(foreign, 1))
        assertTrue(firstGate.onTokenReply(a, 1, 1, "udp_sess_token", token))
        assertEquals(Color4ChromaEvidenceGate.Evidence.HANDSHAKE_ONLY, firstGate.evidence("bulb"))
    }

    @Test fun oneOfFourteenLampsDoesNotGloballyEnableOthers() {
        val g = Color4ChromaEvidenceGate()
        val attempts = (1..14).map { g.beginExplicitOneShot("d$it", Color4ChromaEvidenceGate.Variant.V2_UDP_SESS_NEW)!! }
        assertTrue(g.onTokenReply(attempts[0], 1, 1, "udp_sess_token", token))
        assertTrue(g.recordOneColorFrame(attempts[0], 2))
        assertTrue(g.confirmVisibleColorByOperator(attempts[0], true))
        assertTrue(g.close(attempts[0]))
        for (i in 2..14) assertEquals(Color4ChromaEvidenceGate.Evidence.AWAITING_HANDSHAKE, g.evidence("d$i"))
        assertEquals(Color4ChromaEvidenceGate.Evidence.COLOR_VISUALLY_CONFIRMED, g.evidence("d1"))
    }

    @Test fun uncertainTimeoutIsTerminalOnlyForItsDevice() {
        val g = Color4ChromaEvidenceGate()
        val a = g.beginExplicitOneShot("a", Color4ChromaEvidenceGate.Variant.V1_UDP_NEW)!!
        val b = g.beginExplicitOneShot("b", Color4ChromaEvidenceGate.Variant.V2_UDP_SESS_NEW)!!
        assertTrue(g.onAmbiguousSendOrTimeout(a, 1))
        assertFalse(g.onTokenReply(a, 1, 1, "udp_token", token))
        assertEquals(Color4ChromaEvidenceGate.Evidence.REJECTED_OR_UNCERTAIN, g.evidence("a"))
        assertTrue(g.onTokenReply(b, 1, 1, "udp_sess_token", token))
    }

    @Test fun repeatedResetMustBeExplicitAfterSessionClosure() {
        val g = Color4ChromaEvidenceGate()
        val t = g.beginExplicitOneShot("a", Color4ChromaEvidenceGate.Variant.V1_UDP_NEW)!!
        assertFalse(g.clearOnlyByOperator("a"))
        assertTrue(g.close(t))
        assertTrue(g.clearOnlyByOperator("a"))
        assertFalse(g.clearOnlyByOperator("a"))
        assertEquals(Color4ChromaEvidenceGate.Evidence.UNTESTED, g.evidence("a"))
    }

    @Test fun lateColorErrorAfterCloseRevokesVisiblePass() {
        val g = Color4ChromaEvidenceGate()
        val t = g.beginExplicitOneShot("bulb", Color4ChromaEvidenceGate.Variant.V2_UDP_SESS_NEW)!!
        assertTrue(g.onTokenReply(t, 1, 1, "udp_sess_token", token))
        assertTrue(g.recordOneColorFrame(t, 2))
        assertTrue(g.confirmVisibleColorByOperator(t, true))
        assertTrue(g.close(t))
        assertEquals(Color4ChromaEvidenceGate.Evidence.COLOR_VISUALLY_CONFIRMED, g.evidence("bulb"))
        assertTrue(g.onCorrelatedDeviceError(t, 2))
        assertEquals(Color4ChromaEvidenceGate.Evidence.REJECTED_OR_UNCERTAIN, g.evidence("bulb"))
    }

    @Test fun lateHandshakeErrorAfterColorRevokesVisiblePass() {
        val g = Color4ChromaEvidenceGate()
        val t = g.beginExplicitOneShot("bulb", Color4ChromaEvidenceGate.Variant.V2_UDP_SESS_NEW)!!
        assertTrue(g.onTokenReply(t, 1, 1, "udp_sess_token", token))
        assertTrue(g.recordOneColorFrame(t, 2))
        assertTrue(g.confirmVisibleColorByOperator(t, true))
        assertTrue(g.onCorrelatedDeviceError(t, 1))
        assertEquals(Color4ChromaEvidenceGate.Evidence.REJECTED_OR_UNCERTAIN, g.evidence("bulb"))
    }

    @Test fun staleErrorCannotPoisonOperatorResetOrNextGeneration() {
        val g = Color4ChromaEvidenceGate()
        val first = g.beginExplicitOneShot("bulb", Color4ChromaEvidenceGate.Variant.V2_UDP_SESS_NEW)!!
        assertTrue(g.onTokenReply(first, 1, 1, "udp_sess_token", token))
        assertTrue(g.recordOneColorFrame(first, 2))
        assertTrue(g.close(first))
        assertTrue(g.clearOnlyByOperator("bulb"))
        val second = g.beginExplicitOneShot("bulb", Color4ChromaEvidenceGate.Variant.V2_UDP_SESS_NEW)!!
        assertFalse(g.onCorrelatedDeviceError(first, 2))
        assertFalse(g.onCorrelatedDeviceError(second, 2)) // no color frame sent yet
        assertEquals(Color4ChromaEvidenceGate.Evidence.AWAITING_HANDSHAKE, g.evidence("bulb"))
        assertTrue(g.onCorrelatedDeviceError(second, 1))
        assertEquals(Color4ChromaEvidenceGate.Evidence.REJECTED_OR_UNCERTAIN, g.evidence("bulb"))
    }

    @Test fun massiveStaleReplyAndFramePressureCannotMintSecondColor() {
        val g = Color4ChromaEvidenceGate()
        val t = g.beginExplicitOneShot("bulb", Color4ChromaEvidenceGate.Variant.V2_UDP_SESS_NEW)!!
        assertTrue(g.onTokenReply(t, 1, 1, "udp_sess_token", token))
        var sent = 0
        repeat(5000) {
            if (g.recordOneColorFrame(t, 2)) sent++
            assertFalse(g.onTokenReply(t, 1, 1, "udp_sess_token", token))
        }
        assertEquals(1, sent)
        assertTrue(g.close(t))
        assertEquals(Color4ChromaEvidenceGate.Evidence.REJECTED_OR_UNCERTAIN, g.evidence("bulb"))
    }

    @Test fun staleTimeoutAfterHandshakeAndVisiblePassCannotRevokeSuccess() {
        val g = Color4ChromaEvidenceGate()
        val t = g.beginExplicitOneShot("bulb", Color4ChromaEvidenceGate.Variant.V2_UDP_SESS_NEW)!!
        assertTrue(g.onTokenReply(t, 1, 1, "udp_sess_token", token))
        assertFalse(g.onAmbiguousSendOrTimeout(t, 1)) // handshake already resolved
        assertTrue(g.recordOneColorFrame(t, 2))
        assertTrue(g.confirmVisibleColorByOperator(t, true))
        assertFalse(g.onAmbiguousSendOrTimeout(t, 2)) // no required color ACK
        assertTrue(g.close(t))
        assertFalse(g.onAmbiguousSendOrTimeout(t, 1))
        assertFalse(g.onAmbiguousSendOrTimeout(t, 2))
        assertEquals(Color4ChromaEvidenceGate.Evidence.COLOR_VISUALLY_CONFIRMED, g.evidence("bulb"))
    }

    @Test fun ambiguousColorSendFailsClosedBeforeObservation() {
        val g = Color4ChromaEvidenceGate()
        val t = g.beginExplicitOneShot("bulb", Color4ChromaEvidenceGate.Variant.V2_UDP_SESS_NEW)!!
        assertTrue(g.onTokenReply(t, 1, 1, "udp_sess_token", token))
        assertTrue(g.recordOneColorFrame(t, 2))
        assertTrue(g.onAmbiguousSendOrTimeout(t, 2))
        assertFalse(g.confirmVisibleColorByOperator(t, true))
        assertEquals(Color4ChromaEvidenceGate.Evidence.REJECTED_OR_UNCERTAIN, g.evidence("bulb"))
    }

    @Test fun postcloseCorrelatedErrorDoesNotPoisonOtherLamp() {
        val g = Color4ChromaEvidenceGate()
        val a = g.beginExplicitOneShot("a", Color4ChromaEvidenceGate.Variant.V2_UDP_SESS_NEW)!!
        val b = g.beginExplicitOneShot("b", Color4ChromaEvidenceGate.Variant.V2_UDP_SESS_NEW)!!
        for (t in listOf(a, b)) {
            assertTrue(g.onTokenReply(t, 1, 1, "udp_sess_token", token))
            assertTrue(g.recordOneColorFrame(t, 2))
            assertTrue(g.confirmVisibleColorByOperator(t, true))
            assertTrue(g.close(t))
        }
        assertTrue(g.onCorrelatedDeviceError(a, 2))
        assertEquals(Color4ChromaEvidenceGate.Evidence.REJECTED_OR_UNCERTAIN, g.evidence("a"))
        assertEquals(Color4ChromaEvidenceGate.Evidence.COLOR_VISUALLY_CONFIRMED, g.evidence("b"))
    }

    @Test fun rejectRealIpAddressAndUntrustedIdentityText() {
        val g = Color4ChromaEvidenceGate()
        val variant = Color4ChromaEvidenceGate.Variant.V2_UDP_SESS_NEW
        for (raw in listOf("198.51.100.42", "00:11:22:33:44:55", "user@example.org", "", " test", "a".repeat(33))) {
            assertNull(g.beginExplicitOneShot(raw, variant))
            assertEquals(Color4ChromaEvidenceGate.Evidence.UNTESTED, g.evidence(raw))
        }
        assertNotNull(g.beginExplicitOneShot("lamp_one", variant))
    }
}
