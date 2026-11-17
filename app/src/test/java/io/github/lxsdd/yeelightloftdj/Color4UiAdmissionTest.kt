package io.github.lxsdd.yeelightloftdj

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap

class Color4UiAdmissionTest {
    private val tickets = ConcurrentHashMap<String, Long>()
    private fun take(a: Color4UiAdmission, id: String, kind: Color4UiAdmission.Kind, now: Long, supported: Boolean): Color4UiAdmission.Request? {
        val request = a.takeOnUiEvent(id, kind, now, supported)
        if (request != null) tickets[id] = request.ticket
        return request
    }

    @Test fun requiresExplicitSupportedUiEvent() {
        val a = Color4UiAdmission()
        assertFalse(a.offer("lamp", Color4UiAdmission.Kind.RGB, 1, false))
        assertNull(take(a, "lamp", Color4UiAdmission.Kind.RGB, 0, true))
        assertFalse(a.offer("lamp", Color4UiAdmission.Kind.BRIGHTNESS, 0, true))
        assertTrue(a.offer("lamp", Color4UiAdmission.Kind.RGB, 0x123456, true))
        assertNull(take(a, "lamp", Color4UiAdmission.Kind.RGB, 0, false))
        assertTrue(a.isQuarantined("lamp"))
        a.clearQuarantineByUser("lamp")
        assertNull(take(a, "lamp", Color4UiAdmission.Kind.RGB, 0, true))
        assertTrue(a.offer("lamp", Color4UiAdmission.Kind.RGB, 0x123456, true))
        assertEquals(0x123456, take(a, "lamp", Color4UiAdmission.Kind.RGB, 0, true)?.value)
        assertNull(take(a, "lamp", Color4UiAdmission.Kind.RGB, 1500, true))
    }

    @Test fun latestRgbAndBrightnessStayIndependent() {
        val a = Color4UiAdmission()
        repeat(5000) { a.offer("one", Color4UiAdmission.Kind.RGB, it, true) }
        a.offer("one", Color4UiAdmission.Kind.BRIGHTNESS, 42, true)
        assertEquals(4999, take(a, "one", Color4UiAdmission.Kind.RGB, 0, true)?.value)
        a.complete("one", tickets.getValue("one"), true, false)
        assertEquals(42, take(a, "one", Color4UiAdmission.Kind.BRIGHTNESS, 1500, true)?.value)
    }

    @Test fun noAutomaticRetryAfterTimeoutOrQuota() {
        val a = Color4UiAdmission()
        a.offer("one", Color4UiAdmission.Kind.RGB, 1, true)
        assertNotNull(take(a, "one", Color4UiAdmission.Kind.RGB, 0, true))
        a.offer("one", Color4UiAdmission.Kind.RGB, 2, true)
        a.complete("one", tickets.getValue("one"), false, true)
        assertTrue(a.isQuarantined("one"))
        assertNull(take(a, "one", Color4UiAdmission.Kind.RGB, 600000, true))
        assertFalse(a.offer("one", Color4UiAdmission.Kind.RGB, 3, true))
        a.clearQuarantineByUser("one")
        assertNull(take(a, "one", Color4UiAdmission.Kind.RGB, 600001, true))
        assertTrue(a.offer("one", Color4UiAdmission.Kind.RGB, 4, true))
        assertEquals(4, take(a, "one", Color4UiAdmission.Kind.RGB, 600002, true)?.value)
    }

    @Test fun perLampWindowAndSpacingNeverExceedConservativeBudget() {
        val a = Color4UiAdmission()
        var accepted = 0
        for (t in 0L..59000L step 1500L) {
            a.offer("one", Color4UiAdmission.Kind.RGB, 7, true)
            if (take(a, "one", Color4UiAdmission.Kind.RGB, t, true) != null) {
                accepted++
                a.complete("one", tickets.getValue("one"), true, false)
            }
        }
        assertEquals(24, accepted)
        a.offer("one", Color4UiAdmission.Kind.RGB, 8, true)
        assertNull(take(a, "one", Color4UiAdmission.Kind.RGB, 59999, true))
        assertNotNull(take(a, "one", Color4UiAdmission.Kind.RGB, 60000, true))
    }

    @Test fun fourteenLampFleetIsIndependent() {
        val a = Color4UiAdmission()
        for (i in 0 until 14) {
            val id = "lamp-$i"
            repeat(1000) { a.offer(id, Color4UiAdmission.Kind.RGB, it, true) }
            assertEquals(999, take(a, id, Color4UiAdmission.Kind.RGB, 0, true)?.value)
        }
        a.complete("lamp-0", tickets.getValue("lamp-0"), false, true)
        assertTrue(a.isQuarantined("lamp-0"))
        for (i in 1 until 14) assertFalse(a.isQuarantined("lamp-$i"))
    }

    @Test fun concurrentWritersCanOnlyAcquireOneInflightSlot() {
        val a = Color4UiAdmission()
        val pool = Executors.newFixedThreadPool(24)
        val start = CountDownLatch(1)
        val acquired = AtomicInteger()
        try {
            val tasks = (0 until 24).map { i ->
                pool.submit {
                    start.await()
                    a.offer("one", Color4UiAdmission.Kind.RGB, i, true)
                    if (take(a, "one", Color4UiAdmission.Kind.RGB, 0, true) != null) acquired.incrementAndGet()
                }
            }
            start.countDown()
            tasks.forEach { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, acquired.get())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test fun capabilityLossDropsPendingValuesAndRequiresExplicitRecovery() {
        val a = Color4UiAdmission()
        assertTrue(a.offer("one", Color4UiAdmission.Kind.RGB, 0x123456, true))
        assertFalse(a.offer("one", Color4UiAdmission.Kind.BRIGHTNESS, 12, false))
        assertTrue(a.isQuarantined("one"))
        assertNull(take(a, "one", Color4UiAdmission.Kind.RGB, 0, true))
        a.clearQuarantineByUser("one")
        assertNull(take(a, "one", Color4UiAdmission.Kind.RGB, 0, true))
        assertTrue(a.offer("one", Color4UiAdmission.Kind.RGB, 0x654321, true))
        assertEquals(0x654321, take(a, "one", Color4UiAdmission.Kind.RGB, 0, true)?.value)
        a.complete("one", tickets.getValue("one"), true, false)
        assertTrue(a.offer("one", Color4UiAdmission.Kind.BRIGHTNESS, 25, true))
        assertNull(take(a, "one", Color4UiAdmission.Kind.BRIGHTNESS, 1500, false))
        assertTrue(a.isQuarantined("one"))
    }

    @Test fun backwardClockJumpQuarantinesOnlyAffectedLampAndNeverReplays() {
        val a = Color4UiAdmission()
        for (id in listOf("one", "two")) {
            assertTrue(a.offer(id, Color4UiAdmission.Kind.RGB, 1, true))
            assertNotNull(take(a, id, Color4UiAdmission.Kind.RGB, 10000, true))
            a.complete(id, tickets.getValue(id), true, false)
        }
        assertTrue(a.offer("one", Color4UiAdmission.Kind.RGB, 2, true))
        assertNull(take(a, "one", Color4UiAdmission.Kind.RGB, 9999, true))
        assertTrue(a.isQuarantined("one"))
        assertFalse(a.isQuarantined("two"))
        assertNull(take(a, "one", Color4UiAdmission.Kind.RGB, 20000, true))
        a.clearQuarantineByUser("one")
        assertNull(take(a, "one", Color4UiAdmission.Kind.RGB, 20000, true))
        assertTrue(a.offer("two", Color4UiAdmission.Kind.RGB, 3, true))
        assertEquals(3, take(a, "two", Color4UiAdmission.Kind.RGB, 11500, true)?.value)
    }

    @Test fun staleFailureCannotQuarantineNewerInflightRequest() {
        val a = Color4UiAdmission()
        a.offer("one", Color4UiAdmission.Kind.RGB, 1, true)
        val first = take(a, "one", Color4UiAdmission.Kind.RGB, 0, true)!!
        assertTrue(a.complete("one", first.ticket, true, false))
        a.offer("one", Color4UiAdmission.Kind.RGB, 2, true)
        val second = take(a, "one", Color4UiAdmission.Kind.RGB, 1500, true)!!
        assertFalse(a.complete("one", first.ticket, false, true))
        assertFalse(a.isQuarantined("one"))
        assertNull(take(a, "one", Color4UiAdmission.Kind.RGB, 3000, true))
        assertTrue(a.complete("one", second.ticket, true, false))
    }

    @Test fun duplicateCompletionCannotCloseLaterGeneration() {
        val a = Color4UiAdmission()
        a.offer("one", Color4UiAdmission.Kind.RGB, 1, true)
        val first = take(a, "one", Color4UiAdmission.Kind.RGB, 0, true)!!
        assertTrue(a.complete("one", first.ticket, true, false))
        assertFalse(a.complete("one", first.ticket, true, false))
        a.offer("one", Color4UiAdmission.Kind.RGB, 2, true)
        val second = take(a, "one", Color4UiAdmission.Kind.RGB, 1500, true)!!
        assertFalse(a.complete("one", first.ticket, true, false))
        assertTrue(a.complete("one", second.ticket, true, false))
    }

    @Test fun wrongLampAndUnknownTicketDoNotChangeState() {
        val a = Color4UiAdmission()
        for (id in listOf("one", "two")) a.offer(id, Color4UiAdmission.Kind.RGB, 1, true)
        val one = take(a, "one", Color4UiAdmission.Kind.RGB, 0, true)!!
        val two = take(a, "two", Color4UiAdmission.Kind.RGB, 0, true)!!
        assertFalse(a.complete("two", one.ticket, false, true))
        assertFalse(a.complete("one", -1L, false, true))
        assertFalse(a.isQuarantined("one"))
        assertFalse(a.isQuarantined("two"))
        assertTrue(a.complete("one", one.ticket, true, false))
        assertTrue(a.complete("two", two.ticket, true, false))
    }

    @Test fun lateFailureAfterExplicitQuarantineRecoveryIsIgnored() {
        val a = Color4UiAdmission()
        a.offer("one", Color4UiAdmission.Kind.RGB, 1, true)
        val old = take(a, "one", Color4UiAdmission.Kind.RGB, 0, true)!!
        assertTrue(a.complete("one", old.ticket, false, true))
        assertTrue(a.isQuarantined("one"))
        a.clearQuarantineByUser("one")
        a.offer("one", Color4UiAdmission.Kind.RGB, 2, true)
        val fresh = take(a, "one", Color4UiAdmission.Kind.RGB, 1500, true)!!
        assertFalse(a.complete("one", old.ticket, false, true))
        assertFalse(a.isQuarantined("one"))
        assertTrue(a.complete("one", fresh.ticket, true, false))
    }
    @Test fun ticketExhaustionFailsClosedWithoutReusingRequestGeneration() {
        val a = Color4UiAdmission()
        val field = Color4UiAdmission::class.java.getDeclaredField("nextTicket")
        field.isAccessible = true
        field.setLong(a, Long.MAX_VALUE - 1L)
        assertTrue(a.offer("one", Color4UiAdmission.Kind.RGB, 1, true))
        val last = a.takeOnUiEvent("one", Color4UiAdmission.Kind.RGB, 0L, true)!!
        assertEquals(Long.MAX_VALUE - 1L, last.ticket)
        assertTrue(a.complete("one", last.ticket, true, false))
        assertTrue(a.offer("one", Color4UiAdmission.Kind.RGB, 2, true))
        assertNull(a.takeOnUiEvent("one", Color4UiAdmission.Kind.RGB, 1500L, true))
        assertTrue(a.isQuarantined("one"))
        a.clearQuarantineByUser("one")
        assertTrue(a.offer("one", Color4UiAdmission.Kind.RGB, 3, true))
        assertNull(a.takeOnUiEvent("one", Color4UiAdmission.Kind.RGB, 3000L, true))
        assertTrue(a.isQuarantined("one"))
        assertFalse(a.complete("one", last.ticket, false, true))
    }

    @Test fun ticketExhaustionIsIsolatedFromOtherAlreadyInflightLamp() {
        val a = Color4UiAdmission()
        assertTrue(a.offer("other", Color4UiAdmission.Kind.RGB, 9, true))
        val other = a.takeOnUiEvent("other", Color4UiAdmission.Kind.RGB, 0L, true)!!
        val field = Color4UiAdmission::class.java.getDeclaredField("nextTicket")
        field.isAccessible = true
        field.setLong(a, Long.MAX_VALUE)
        assertTrue(a.offer("exhausted", Color4UiAdmission.Kind.RGB, 5, true))
        assertNull(a.takeOnUiEvent("exhausted", Color4UiAdmission.Kind.RGB, 0L, true))
        assertTrue(a.isQuarantined("exhausted"))
        assertFalse(a.isQuarantined("other"))
        assertTrue(a.complete("other", other.ticket, true, false))
    }

    @Test fun capabilityLossDuringInflightNeverReleasesSlotOrReplaysPending() {
        val a = Color4UiAdmission()
        assertTrue(a.offer("one", Color4UiAdmission.Kind.RGB, 1, true))
        val first = a.takeOnUiEvent("one", Color4UiAdmission.Kind.RGB, 0L, true)!!
        assertTrue(a.offer("one", Color4UiAdmission.Kind.RGB, 2, true))
        assertFalse(a.offer("one", Color4UiAdmission.Kind.BRIGHTNESS, 50, false))
        assertTrue(a.isQuarantined("one"))
        a.clearQuarantineByUser("one")
        assertTrue(a.isQuarantined("one"))
        assertNull(a.takeOnUiEvent("one", Color4UiAdmission.Kind.RGB, 1500L, true))
        assertTrue(a.complete("one", first.ticket, true, false))
        assertTrue(a.isQuarantined("one"))
        a.clearQuarantineByUser("one")
        assertNull(a.takeOnUiEvent("one", Color4UiAdmission.Kind.RGB, 3000L, true))
        assertNull(a.takeOnUiEvent("one", Color4UiAdmission.Kind.BRIGHTNESS, 3000L, true))
        assertTrue(a.offer("one", Color4UiAdmission.Kind.RGB, 3, true))
        assertEquals(3, a.takeOnUiEvent("one", Color4UiAdmission.Kind.RGB, 3000L, true)?.value)
    }

    @Test fun deniedAttemptsNeverConsumeBudgetOrAdvanceSpacing() {
        val a = Color4UiAdmission()
        assertFalse(a.offer("one", Color4UiAdmission.Kind.RGB, -1, true))
        assertFalse(a.offer("one", Color4UiAdmission.Kind.BRIGHTNESS, 101, true))
        assertNull(a.takeOnUiEvent("one", Color4UiAdmission.Kind.RGB, 0L, true))
        assertTrue(a.offer("one", Color4UiAdmission.Kind.RGB, 1, true))
        val first = a.takeOnUiEvent("one", Color4UiAdmission.Kind.RGB, 0L, true)!!
        assertNull(a.takeOnUiEvent("one", Color4UiAdmission.Kind.RGB, 1500L, true))
        assertTrue(a.complete("one", first.ticket, true, false))
        assertTrue(a.offer("one", Color4UiAdmission.Kind.RGB, 2, true))
        assertNull(a.takeOnUiEvent("one", Color4UiAdmission.Kind.RGB, 1499L, true))
        assertEquals(2, a.takeOnUiEvent("one", Color4UiAdmission.Kind.RGB, 1500L, true)?.value)
    }

    @Test fun staleFailureCannotAffectAnotherLampAfterRecovery() {
        val a = Color4UiAdmission()
        for (id in listOf("one", "two")) assertTrue(a.offer(id, Color4UiAdmission.Kind.RGB, 1, true))
        val first = a.takeOnUiEvent("one", Color4UiAdmission.Kind.RGB, 0L, true)!!
        val second = a.takeOnUiEvent("two", Color4UiAdmission.Kind.RGB, 0L, true)!!
        assertTrue(a.complete("one", first.ticket, false, true))
        a.clearQuarantineByUser("one")
        assertFalse(a.complete("one", first.ticket, false, true))
        assertFalse(a.complete("two", first.ticket, false, true))
        assertFalse(a.isQuarantined("two"))
        assertTrue(a.complete("two", second.ticket, true, false))
    }

}
