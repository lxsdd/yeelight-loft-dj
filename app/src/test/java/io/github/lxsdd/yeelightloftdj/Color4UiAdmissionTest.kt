package io.github.lxsdd.yeelightloftdj

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class Color4UiAdmissionTest {
    @Test fun requiresExplicitSupportedUiEvent() {
        val a = Color4UiAdmission()
        assertFalse(a.offer("lamp", Color4UiAdmission.Kind.RGB, 1, false))
        assertNull(a.takeOnUiEvent("lamp", Color4UiAdmission.Kind.RGB, 0, true))
        assertFalse(a.offer("lamp", Color4UiAdmission.Kind.BRIGHTNESS, 0, true))
        assertTrue(a.offer("lamp", Color4UiAdmission.Kind.RGB, 0x123456, true))
        assertNull(a.takeOnUiEvent("lamp", Color4UiAdmission.Kind.RGB, 0, false))
        assertTrue(a.isQuarantined("lamp"))
        a.clearQuarantineByUser("lamp")
        assertNull(a.takeOnUiEvent("lamp", Color4UiAdmission.Kind.RGB, 0, true))
        assertTrue(a.offer("lamp", Color4UiAdmission.Kind.RGB, 0x123456, true))
        assertEquals(0x123456, a.takeOnUiEvent("lamp", Color4UiAdmission.Kind.RGB, 0, true)?.value)
        assertNull(a.takeOnUiEvent("lamp", Color4UiAdmission.Kind.RGB, 1500, true))
    }

    @Test fun latestRgbAndBrightnessStayIndependent() {
        val a = Color4UiAdmission()
        repeat(5000) { a.offer("one", Color4UiAdmission.Kind.RGB, it, true) }
        a.offer("one", Color4UiAdmission.Kind.BRIGHTNESS, 42, true)
        assertEquals(4999, a.takeOnUiEvent("one", Color4UiAdmission.Kind.RGB, 0, true)?.value)
        a.complete("one", true, false)
        assertEquals(42, a.takeOnUiEvent("one", Color4UiAdmission.Kind.BRIGHTNESS, 1500, true)?.value)
    }

    @Test fun noAutomaticRetryAfterTimeoutOrQuota() {
        val a = Color4UiAdmission()
        a.offer("one", Color4UiAdmission.Kind.RGB, 1, true)
        assertNotNull(a.takeOnUiEvent("one", Color4UiAdmission.Kind.RGB, 0, true))
        a.offer("one", Color4UiAdmission.Kind.RGB, 2, true)
        a.complete("one", false, true)
        assertTrue(a.isQuarantined("one"))
        assertNull(a.takeOnUiEvent("one", Color4UiAdmission.Kind.RGB, 600000, true))
        assertFalse(a.offer("one", Color4UiAdmission.Kind.RGB, 3, true))
        a.clearQuarantineByUser("one")
        assertNull(a.takeOnUiEvent("one", Color4UiAdmission.Kind.RGB, 600001, true))
        assertTrue(a.offer("one", Color4UiAdmission.Kind.RGB, 4, true))
        assertEquals(4, a.takeOnUiEvent("one", Color4UiAdmission.Kind.RGB, 600002, true)?.value)
    }

    @Test fun perLampWindowAndSpacingNeverExceedConservativeBudget() {
        val a = Color4UiAdmission()
        var accepted = 0
        for (t in 0L..59000L step 1500L) {
            a.offer("one", Color4UiAdmission.Kind.RGB, 7, true)
            if (a.takeOnUiEvent("one", Color4UiAdmission.Kind.RGB, t, true) != null) {
                accepted++
                a.complete("one", true, false)
            }
        }
        assertEquals(24, accepted)
        a.offer("one", Color4UiAdmission.Kind.RGB, 8, true)
        assertNull(a.takeOnUiEvent("one", Color4UiAdmission.Kind.RGB, 59999, true))
        assertNotNull(a.takeOnUiEvent("one", Color4UiAdmission.Kind.RGB, 60000, true))
    }

    @Test fun fourteenLampFleetIsIndependent() {
        val a = Color4UiAdmission()
        for (i in 0 until 14) {
            val id = "lamp-$i"
            repeat(1000) { a.offer(id, Color4UiAdmission.Kind.RGB, it, true) }
            assertEquals(999, a.takeOnUiEvent(id, Color4UiAdmission.Kind.RGB, 0, true)?.value)
        }
        a.complete("lamp-0", false, true)
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
                    if (a.takeOnUiEvent("one", Color4UiAdmission.Kind.RGB, 0, true) != null) acquired.incrementAndGet()
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
        assertNull(a.takeOnUiEvent("one", Color4UiAdmission.Kind.RGB, 0, true))
        a.clearQuarantineByUser("one")
        assertNull(a.takeOnUiEvent("one", Color4UiAdmission.Kind.RGB, 0, true))
        assertTrue(a.offer("one", Color4UiAdmission.Kind.RGB, 0x654321, true))
        assertEquals(0x654321, a.takeOnUiEvent("one", Color4UiAdmission.Kind.RGB, 0, true)?.value)
        a.complete("one", true, false)
        assertTrue(a.offer("one", Color4UiAdmission.Kind.BRIGHTNESS, 25, true))
        assertNull(a.takeOnUiEvent("one", Color4UiAdmission.Kind.BRIGHTNESS, 1500, false))
        assertTrue(a.isQuarantined("one"))
    }

    @Test fun backwardClockJumpQuarantinesOnlyAffectedLampAndNeverReplays() {
        val a = Color4UiAdmission()
        for (id in listOf("one", "two")) {
            assertTrue(a.offer(id, Color4UiAdmission.Kind.RGB, 1, true))
            assertNotNull(a.takeOnUiEvent(id, Color4UiAdmission.Kind.RGB, 10000, true))
            a.complete(id, true, false)
        }
        assertTrue(a.offer("one", Color4UiAdmission.Kind.RGB, 2, true))
        assertNull(a.takeOnUiEvent("one", Color4UiAdmission.Kind.RGB, 9999, true))
        assertTrue(a.isQuarantined("one"))
        assertFalse(a.isQuarantined("two"))
        assertNull(a.takeOnUiEvent("one", Color4UiAdmission.Kind.RGB, 20000, true))
        a.clearQuarantineByUser("one")
        assertNull(a.takeOnUiEvent("one", Color4UiAdmission.Kind.RGB, 20000, true))
        assertTrue(a.offer("two", Color4UiAdmission.Kind.RGB, 3, true))
        assertEquals(3, a.takeOnUiEvent("two", Color4UiAdmission.Kind.RGB, 11500, true)?.value)
    }
}
