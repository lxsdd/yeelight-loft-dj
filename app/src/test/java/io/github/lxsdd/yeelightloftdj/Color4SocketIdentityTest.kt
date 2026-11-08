package io.github.lxsdd.yeelightloftdj

import org.junit.Assert.*
import org.junit.Test

/** Offline-only regression: the socket reference, not its public generation, owns ACKs. */
class Color4SocketIdentityTest {
    @Test fun copiedGenerationCannotCompleteOrCloseRealLedgerSession() {
        val l = Color4TcpResponseLedger()
        val real = l.open("one")!!
        val counterfeit = Color4TcpResponseLedger.Session(real.generation)
        assertFalse(real === counterfeit)
        assertTrue(l.begin("one", real, 11, 7))
        assertNull(l.reply("one", counterfeit, 7, true, false))
        assertNull(l.timeout("one", counterfeit, 7))
        assertNull(l.close("one", counterfeit))
        assertFalse(l.isQuarantined("one"))
        assertEquals(11L, l.reply("one", real, 7, true, false)?.ticket)
        assertNull(l.reply("one", counterfeit, 7, false, true))
    }

    @Test fun forgedSessionCannotOpenOrSpendCommandSlot() {
        val l = Color4TcpResponseLedger()
        val real = l.open("one")!!
        val counterfeit = Color4TcpResponseLedger.Session(real.generation)
        assertFalse(l.begin("one", counterfeit, 100, 1))
        assertTrue(l.begin("one", real, 1, 1))
        assertEquals(1L, l.reply("one", real, 1, true, false)?.ticket)
        assertTrue(l.begin("one", real, 2, 2))
        assertEquals(2L, l.reply("one", real, 2, true, false)?.ticket)
    }

    @Test fun coordinatorRejectsCounterfeitOnAllCallbackPaths() {
        val c = Color4OfflineCoordinator()
        val real = c.openSession("one", true)!!
        val fake = Color4TcpResponseLedger.Session(real.generation)
        assertTrue(c.offer("one", Color4UiAdmission.Kind.RGB, 0x123456, true))
        assertNull(c.takeOnExplicitUiEvent("one", Color4UiAdmission.Kind.RGB, 10_000L, true, fake, 1))
        assertFalse(c.closeSession("one", fake))
        val sent = c.takeOnExplicitUiEvent("one", Color4UiAdmission.Kind.RGB, 10_000L, true, real, 1)!!
        assertEquals(0x123456, sent.value)
        assertFalse(c.onReply("one", fake, 1, true, false))
        assertFalse(c.onTimeout("one", fake, 1))
        assertFalse(c.closeSession("one", fake))
        assertFalse(c.isQuarantined("one"))
        assertTrue(c.onReply("one", real, 1, true, false))
        assertTrue(c.closeSession("one", real))
    }

    @Test fun sameGenerationFromAnotherLedgerCannotHijackOwnSocket() {
        val a = Color4TcpResponseLedger()
        val b = Color4TcpResponseLedger()
        val original = a.open("bulb")!!
        val other = b.open("bulb")!!
        assertEquals(original.generation, other.generation)
        assertTrue(a.begin("bulb", original, 1, 5))
        assertNull(a.reply("bulb", other, 5, true, false))
        assertNull(a.close("bulb", other))
        assertEquals(1L, a.reply("bulb", original, 5, true, false)?.ticket)
    }

    @Test fun thousandsOfForgedCallbacksRemainIsolatedFromOtherBulbs() {
        val c = Color4OfflineCoordinator()
        val real = (0 until 14).associate { i -> "lamp-$i" to c.openSession("lamp-$i", true)!! }
        for (i in 0 until 14) {
            val id = "lamp-$i"
            assertTrue(c.offer(id, Color4UiAdmission.Kind.RGB, 0x100 + i, true))
            assertNotNull(c.takeOnExplicitUiEvent(id, Color4UiAdmission.Kind.RGB, 0L, true, real.getValue(id), 1))
        }
        repeat(5_600) { i ->
            val key = "lamp-${i % 14}"
            val forged = Color4TcpResponseLedger.Session(real.getValue(key).generation)
            assertFalse(c.onReply(key, forged, 1, true, false))
            assertFalse(c.onTimeout(key, forged, 1))
            assertFalse(c.closeSession(key, forged))
        }
        for (i in 0 until 14) {
            val id = "lamp-$i"
            assertFalse(c.isQuarantined(id))
            assertTrue(c.onReply(id, real.getValue(id), 1, true, false))
            assertTrue(c.closeSession(id, real.getValue(id)))
        }
    }
}
