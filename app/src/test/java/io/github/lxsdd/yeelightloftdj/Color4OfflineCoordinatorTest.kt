package io.github.lxsdd.yeelightloftdj

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class Color4OfflineCoordinatorTest {
    private val rgb = Color4UiAdmission.Kind.RGB
    private val brightness = Color4UiAdmission.Kind.BRIGHTNESS

    @Test fun preSessionIntentsCannotBeReplayedByFreshExplicitUiEvent() {
        val c = Color4OfflineCoordinator()
        assertFalse(c.offer("one", rgb, 0x123456, true))
        assertFalse(c.offer("one", brightness, 44, true))
        val s = c.openSession("one", true)!!
        assertNull(c.takeOnExplicitUiEvent("one", rgb, 10_000, true, s, 1))
        assertNull(c.takeOnExplicitUiEvent("one", brightness, 10_000, true, s, 1))
        assertTrue(c.offer("one", rgb, 0xabcdef, true))
        assertEquals(0xabcdef, c.takeOnExplicitUiEvent("one", rgb, 10_000, true, s, 1)!!.value)
        assertTrue(c.onReply("one", s, 1, true, false))
        assertNull(c.takeOnExplicitUiEvent("one", brightness, 12_000, true, s, 2))
        assertTrue(c.closeSession("one", s))
        assertFalse(c.offer("one", rgb, 0x010203, true))
        val next = c.openSession("one", true)!!
        assertNull(c.takeOnExplicitUiEvent("one", rgb, 13_500, true, next, 2))
        assertTrue(c.offer("one", rgb, 0x112233, true))
        assertEquals(0x112233, c.takeOnExplicitUiEvent("one", rgb, 13_500, true, next, 2)!!.value)
        assertTrue(c.onReply("one", next, 2, true, false))
    }

    @Test fun explicitUiTicketAndExactReplyOnly() {
        val c = Color4OfflineCoordinator()
        val s = c.openSession("one", true)!!
        assertTrue(c.offer("one", rgb, 0x123456, true))
        val first = c.takeOnExplicitUiEvent("one", rgb, 10_000, true, s, 7)!!
        assertEquals(0x123456, first.value)
        assertTrue(first.ticket > 0)
        assertFalse(c.onReply("one", s, 8, true, false))
        assertFalse(c.onReply("two", s, 7, true, false))
        assertNull(c.takeOnExplicitUiEvent("one", rgb, 11_500, true, s, 8))
        assertTrue(c.onReply("one", s, 7, true, false))
        assertFalse(c.onReply("one", s, 7, false, true))
        assertNull(c.takeOnExplicitUiEvent("one", rgb, 11_500, true, s, 8))
        assertTrue(c.offer("one", brightness, 75, true))
        assertEquals(75, c.takeOnExplicitUiEvent("one", brightness, 11_500, true, s, 8)!!.value)
        assertTrue(c.onReply("one", s, 8, true, false))
        assertFalse(c.isQuarantined("one"))
    }

    @Test fun cleanDisconnectDiscardsPendingValuesAcrossSocketGenerations() {
        val c = Color4OfflineCoordinator()
        val a = c.openSession("one", true)!!
        val b = c.openSession("two", true)!!
        assertTrue(c.offer("one", rgb, 0xabcdef, true))
        assertTrue(c.offer("one", brightness, 55, true))
        assertTrue(c.offer("two", rgb, 0x123456, true))
        assertFalse(c.closeSession("one", b))
        assertTrue(c.closeSession("one", a))
        assertFalse(c.isQuarantined("one"))
        val fresh = c.openSession("one", true)!!
        assertNull(c.takeOnExplicitUiEvent("one", rgb, 10_000, true, fresh, 1))
        assertNull(c.takeOnExplicitUiEvent("one", brightness, 10_000, true, fresh, 1))
        assertNotNull(c.takeOnExplicitUiEvent("two", rgb, 10_000, true, b, 1))
        assertTrue(c.onReply("two", b, 1, true, false))
        assertTrue(c.offer("one", rgb, 0x111111, true))
        assertEquals(0x111111, c.takeOnExplicitUiEvent("one", rgb, 10_000, true, fresh, 1)!!.value)
        assertTrue(c.onReply("one", fresh, 1, true, false))
    }

    @Test fun failedLedgerBeginConsumesBudgetAndMustRecover() {
        val c = Color4OfflineCoordinator()
        val s = c.openSession("one", true)!!
        assertTrue(c.offer("one", rgb, 10, true))
        assertNull(c.takeOnExplicitUiEvent("one", rgb, 1_000, true, s, 0))
        assertTrue(c.isQuarantined("one"))
        assertNull(c.openSession("one", true))
        assertFalse(c.offer("one", rgb, 11, true))
        assertTrue(c.clearQuarantineByUser("one"))
        val fresh = c.openSession("one", true)!!
        assertTrue(fresh.generation > s.generation)
        assertFalse(c.onReply("one", s, 1, true, false))
        assertTrue(c.offer("one", rgb, 20, true))
        assertNull(c.takeOnExplicitUiEvent("one", rgb, 2_499, true, fresh, 1))
        assertEquals(20, c.takeOnExplicitUiEvent("one", rgb, 2_500, true, fresh, 1)!!.value)
        assertTrue(c.onReply("one", fresh, 1, true, false))
    }

    @Test fun timeoutDisconnectCapabilityLossAndBackwardTimeFailClosed() {
        val c = Color4OfflineCoordinator()
        val a = c.openSession("one", true)!!
        val b = c.openSession("two", true)!!
        assertTrue(c.offer("one", rgb, 1, true))
        assertTrue(c.offer("two", rgb, 2, true))
        val first = c.takeOnExplicitUiEvent("one", rgb, 10_000, true, a, 1)!!
        assertNotNull(c.takeOnExplicitUiEvent("two", rgb, 10_000, true, b, 1))
        assertFalse(c.onTimeout("one", b, 1))
        assertTrue(c.onTimeout("one", a, 1))
        assertTrue(c.isQuarantined("one"))
        assertFalse(c.isQuarantined("two"))
        assertTrue(c.onReply("two", b, 1, true, false))
        assertFalse(c.onReply("one", a, 1, true, false))
        assertFalse(c.clearQuarantineByUser("one"))
        assertTrue(c.closeSession("one", a))
        assertTrue(c.clearQuarantineByUser("one"))
        val fresh = c.openSession("one", true)!!
        assertTrue(c.offer("one", rgb, 3, true))
        assertNull(c.takeOnExplicitUiEvent("one", rgb, 9_999, true, fresh, 1))
        assertTrue(c.isQuarantined("one"))
        assertTrue(c.closeSession("one", fresh))
        assertTrue(c.clearQuarantineByUser("one"))
        val last = c.openSession("one", true)!!
        assertTrue(c.offer("one", brightness, 55, true))
        assertNull(c.takeOnExplicitUiEvent("one", brightness, 12_000, false, last, 1))
        assertTrue(c.isQuarantined("one"))
        assertFalse(c.onReply("one", a, 1, true, false))
        assertTrue(first.ticket > 0L)
    }

    @Test fun twentyFourPerMinuteAndMinimumSpacingPreserved() {
        val c = Color4OfflineCoordinator()
        val s = c.openSession("one", true)!!
        for (i in 0 until 24) {
            assertTrue(c.offer("one", rgb, i, true))
            assertNotNull(c.takeOnExplicitUiEvent("one", rgb, i * 1500L, true, s, i + 1L))
            assertTrue(c.onReply("one", s, i + 1L, true, false))
        }
        assertTrue(c.offer("one", rgb, 99, true))
        assertNull(c.takeOnExplicitUiEvent("one", rgb, 36_000, true, s, 25))
        assertNotNull(c.takeOnExplicitUiEvent("one", rgb, 60_000, true, s, 25))
        assertTrue(c.onReply("one", s, 25, true, false))
    }

    @Test fun fourteenDevicesAnd5600ExplicitUiEvents() {
        val c = Color4OfflineCoordinator()
        val sessions = (0 until 14).associate { n -> "lamp-$n" to c.openSession("lamp-$n", true)!! }
        var dispatched = 0
        for (event in 0 until 400) {
            for (n in 0 until 14) {
                val id = "lamp-$n"
                assertTrue(c.offer(id, rgb, (event + n) and 0xffffff, true))
                assertNotNull(c.takeOnExplicitUiEvent(id, rgb, event * 3000L, true, sessions.getValue(id), event + 1L))
                assertTrue(c.onReply(id, sessions.getValue(id), event + 1L, true, false))
                ++dispatched
            }
        }
        assertEquals(5600, dispatched)
        for (id in sessions.keys) assertFalse(c.isQuarantined(id))
    }

    @Test fun parallelUiInputsNeverGainTwoInflightSlots() {
        val c = Color4OfflineCoordinator()
        val s = c.openSession("one", true)!!
        val pool = Executors.newFixedThreadPool(20)
        val start = CountDownLatch(1)
        val dispatched = AtomicInteger()
        try {
            val actions = (0 until 20).map { i -> pool.submit {
                start.await()
                c.offer("one", rgb, i, true)
                if (c.takeOnExplicitUiEvent("one", rgb, 0, true, s, i + 1L) != null) dispatched.incrementAndGet()
            } }
            start.countDown()
            actions.forEach { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, dispatched.get())
        } finally { pool.shutdownNow() }
    }
}
