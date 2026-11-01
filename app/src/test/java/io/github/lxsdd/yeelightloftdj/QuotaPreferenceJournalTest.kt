package io.github.lxsdd.yeelightloftdj

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class QuotaPreferenceJournalTest {
    @Test fun simultaneousDifferentBulbsNeverLosePersistentQuarantineEntries() {
        var disk = emptySet<String>()
        val journal = QuotaPreferenceJournal(
            readBlocked = { disk },
            commitBlocked = { disk = it; true },
        )
        val threads = (0 until 32).map { n ->
            thread(start = false) { journal.update("device-$n", true) }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(2_000L) }
        assertTrue(threads.none { it.isAlive })
        assertEquals((0 until 32).map { "device-$it" }.toSet(), journal.snapshot())
        journal.update("device-0", false)
        assertFalse("device-0" in journal.snapshot())
        assertEquals(31, journal.snapshot().size)
    }

    @Test fun failedDurableCommitNeverReleasesFirmwareGate() {
        var stored = setOf("blocked")
        var canCommit = false
        val journal = QuotaPreferenceJournal(
            readBlocked = { stored },
            commitBlocked = {
                if (!canCommit) false else { stored = it; true }
            },
        )
        val gate = DeviceCommandGate(nowMs = { 50_000L }, minSpacingMs = 0,
            onRecovered = { journal.update(it, false) })
        gate.markQuarantined("blocked")
        val rejected = runCatching {
            gate.explicitRecoveryProbe("blocked") { "on" }
        }.exceptionOrNull()
        assertTrue(rejected is IllegalStateException)
        assertTrue(gate.isQuarantined("blocked"))
        assertEquals(setOf("blocked"), stored)
        canCommit = true
        // The first (possibly delivered) read counts as the one recovery
        // attempt this minute; it must not be blindly repeated.
        assertTrue(runCatching {
            gate.explicitRecoveryProbe("blocked") { "on" }
        }.exceptionOrNull() is DeviceCommandGate.Deferred)
    }

    @Test fun perBulbQuotaCallbackCannotWaitForGlobalLanMonitor() {
        var stored = emptySet<String>()
        val journal = QuotaPreferenceJournal(
            readBlocked = { stored },
            commitBlocked = { stored = it; true },
        )
        val gate = DeviceCommandGate(nowMs = { 10_000L }, minSpacingMs = 0,
            onQuarantined = { journal.update(it, true) })
        val insideGate = CountDownLatch(1)
        val allowRejection = CountDownLatch(1)
        val insideGlobal = CountDownLatch(1)

        val quotaThread = thread(isDaemon = true) {
            runCatching {
                gate.run("bulb") {
                    insideGate.countDown()
                    check(allowRejection.await(3, TimeUnit.SECONDS))
                    error("client quota exceeded")
                }
            }
        }
        assertTrue(insideGate.await(2, TimeUnit.SECONDS))

        // Simulates the YeelightLan @Synchronized Music-Mode methods:
        // they hold the LAN monitor while trying to enter the gate.
        val musicThread = thread(isDaemon = true) {
            synchronized(YeelightLan) {
                insideGlobal.countDown()
                runCatching { gate.run("bulb") { } }
            }
        }
        assertTrue(insideGlobal.await(2, TimeUnit.SECONDS))
        allowRejection.countDown()
        quotaThread.join(2_000L)
        musicThread.join(2_000L)
        assertFalse("quota callback stalled behind global monitor", quotaThread.isAlive)
        assertFalse("music operation stalled behind quota callback", musicThread.isAlive)
        assertEquals(setOf("bulb"), stored)
        assertTrue(gate.isQuarantined("bulb"))
    }
}
