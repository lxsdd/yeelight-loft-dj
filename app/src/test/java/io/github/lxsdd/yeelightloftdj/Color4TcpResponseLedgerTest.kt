package io.github.lxsdd.yeelightloftdj

import org.junit.Assert.*
import org.junit.Test

class Color4TcpResponseLedgerTest {
    @Test fun replyCorrelatesOnlyExactSocketAndProtocolId() {
        val l = Color4TcpResponseLedger()
        val a = l.open("one")!!
        val b = l.open("two")!!
        assertTrue(l.begin("one", a, 11, 91))
        assertNull(l.reply("one", b, 91, true, false))
        assertNull(l.reply("one", a, 92, true, false))
        assertEquals(11L, l.reply("one", a, 91, true, false)?.ticket)
        assertNull(l.reply("one", a, 91, false, true))
        assertFalse(l.isQuarantined("one"))
    }

    @Test fun disconnectQuarantinesUncertainWriteWithoutReplay() {
        val l = Color4TcpResponseLedger()
        val a = l.open("one")!!
        assertTrue(l.begin("one", a, 1, 4))
        assertEquals(Color4TcpResponseLedger.Completion(1, false, true), l.close("one", a))
        assertNull(l.open("one"))
        assertNull(l.reply("one", a, 4, true, false))
        assertTrue(l.clearQuarantineByUser("one"))
        val b = l.open("one")!!
        assertTrue(b.generation > a.generation)
        assertNull(l.reply("one", a, 4, false, true))
        assertTrue(l.begin("one", b, 2, 4))
        assertEquals(2L, l.reply("one", b, 4, true, false)?.ticket)
    }

    @Test fun timeoutIsIdempotentAndNeverFinishesNewRequest() {
        val l = Color4TcpResponseLedger()
        val a = l.open("one")!!
        assertTrue(l.begin("one", a, 1, 7))
        assertNull(l.timeout("one", a, 8))
        assertEquals(1L, l.timeout("one", a, 7)?.ticket)
        assertNull(l.timeout("one", a, 7))
        assertFalse(l.begin("one", a, 2, 8))
        assertNull(l.open("one"))
    }

    @Test fun wrongSessionCannotCloseNewSession() {
        val l = Color4TcpResponseLedger()
        val a = l.open("one")!!
        assertNull(l.close("one", a))
        val b = l.open("one")!!
        assertTrue(l.begin("one", b, 3, 12))
        assertNull(l.close("one", a))
        assertEquals(3L, l.reply("one", b, 12, true, false)?.ticket)
    }

    @Test fun malformedRequestsAndConcurrentSessionsFailClosed() {
        val l = Color4TcpResponseLedger()
        assertNull(l.open(""))
        val a = l.open("one")!!
        assertNull(l.open("one"))
        assertFalse(l.begin("one", a, 0, 1))
        assertFalse(l.begin("one", a, 1, 0))
        assertTrue(l.begin("one", a, 1, 1))
        assertFalse(l.begin("one", a, 2, 2))
        assertEquals(1L, l.reply("one", a, 1, true, false)?.ticket)
        assertTrue(l.begin("one", a, 2, 2))
        assertEquals(2L, l.reply("one", a, 2, true, false)?.ticket)
        assertFalse(l.begin("one", a, 3, 2))
        assertTrue(l.begin("one", a, 3, 3))
    }

    @Test fun failureIsIsolatedAcrossFourteenLamps() {
        val l = Color4TcpResponseLedger()
        val sessions = (0 until 14).map { l.open("lamp-$it")!! }
        for (i in 0 until 14) assertTrue(l.begin("lamp-$i", sessions[i], i + 1L, 5))
        assertEquals(1L, l.timeout("lamp-0", sessions[0], 5)?.ticket)
        assertTrue(l.isQuarantined("lamp-0"))
        for (i in 1 until 14) {
            assertFalse(l.isQuarantined("lamp-$i"))
            assertEquals(i + 1L, l.reply("lamp-$i", sessions[i], 5, true, false)?.ticket)
        }
    }

    @Test fun spentAdmissionTicketCannotBeReusedWithinSession() {
        val l = Color4TcpResponseLedger()
        val s = l.open("one")!!
        assertTrue(l.begin("one", s, 10, 1))
        assertEquals(10L, l.reply("one", s, 1, true, false)?.ticket)
        assertFalse(l.begin("one", s, 10, 2))
        assertFalse(l.begin("one", s, 9, 2))
        assertTrue(l.begin("one", s, 11, 2))
        assertEquals(11L, l.reply("one", s, 2, true, false)?.ticket)
    }

    @Test fun spentTicketCannotReplayAfterReconnectOrQuarantineRecovery() {
        val l = Color4TcpResponseLedger()
        val first = l.open("one")!!
        assertTrue(l.begin("one", first, 77, 1))
        assertEquals(77L, l.close("one", first)?.ticket)
        assertTrue(l.isQuarantined("one"))
        assertTrue(l.clearQuarantineByUser("one"))
        val second = l.open("one")!!
        assertFalse(l.begin("one", second, 77, 1))
        assertFalse(l.begin("one", second, 76, 1))
        assertTrue(l.begin("one", second, 78, 1))
        assertEquals(78L, l.reply("one", second, 1, true, false)?.ticket)
        assertNull(l.reply("one", first, 1, false, true))
    }

    @Test fun rejectedReplayNeverConsumesProtocolIdOrAffectsOtherLamp() {
        val l = Color4TcpResponseLedger()
        val a = l.open("one")!!
        val b = l.open("two")!!
        assertTrue(l.begin("one", a, 3, 1))
        assertEquals(3L, l.reply("one", a, 1, true, false)?.ticket)
        assertFalse(l.begin("one", a, 3, 100))
        assertTrue(l.begin("one", a, 4, 2))
        assertTrue(l.begin("two", b, 3, 1))
        assertEquals(4L, l.reply("one", a, 2, true, false)?.ticket)
        assertEquals(3L, l.reply("two", b, 1, true, false)?.ticket)
        assertFalse(l.isQuarantined("one"))
        assertFalse(l.isQuarantined("two"))
    }

}
