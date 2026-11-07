package io.github.lxsdd.yeelightloftdj

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class DeviceCommandGateTest {
    @Test fun commandsArePacedAndNeverBurstWithinRollingBudget() {
        var now = 10_000L
        val waits = mutableListOf<Long>()
        val gate = DeviceCommandGate(nowMs = { now }, maxCommandsPerMinute = 3,
            sleepMs = { duration -> waits.add(duration); now += duration })
        val actualSendTimes = mutableListOf<Long>()
        // Two physical packets (set_power and get_prop) require distinct
        // paced admissions; one batched reservation cannot enforce this.
        gate.run("Wellness") { actualSendTimes.add(now) }
        gate.run("Wellness") { actualSendTimes.add(now) }
        gate.run("Wellness") { actualSendTimes.add(now) }
        assertEquals(listOf(1_500L, 1_500L), waits)
        assertEquals(listOf(10_000L, 11_500L, 13_000L), actualSendTimes)
        assertEquals(3, gate.consumed("Wellness"))
        assertTrue(runCatching { gate.run("Wellness") { } }.exceptionOrNull()
            is DeviceCommandGate.Deferred)
        // Neither remote socket nor app-side traffic from other bulbs shares
        // the local budget of Wellness.
        gate.run("Couchtisch", 2) { }
        now += 60_001L
        gate.run("Wellness") { }
        assertEquals(1, gate.consumed("Wellness"))
    }

    @Test fun blockedLocalAdmissionNeverExecutesUnderlyingPowerCommand() {
        val gate = DeviceCommandGate(nowMs = { 10_000L }, minSpacingMs = 0)
        gate.markQuarantined("Wellness")
        var socketWrites = 0
        val error = runCatching {
            gate.run("Wellness") { socketWrites++ }
        }.exceptionOrNull()
        assertTrue(error is DeviceCommandGate.Deferred)
        assertEquals(0, socketWrites)
        assertEquals(0, gate.consumed("Wellness"))
        // No shared global block for a different healthy Color4.
        gate.run("Couchtisch") { socketWrites++ }
        assertEquals(1, socketWrites)
    }

    @Test fun realFirmwareQuotaPersistsAfterOneMinuteAndIsPerBulb() {
        var now = 20_000L
        val recorded = mutableListOf<String>()
        val gate = DeviceCommandGate(nowMs = { now }, maxCommandsPerMinute = 4,
            onQuarantined = { recorded.add(it) })
        assertTrue(runCatching { gate.run("Wellness") {
            error("set_rgb abgelehnt: client quota exceeded")
        } }.isFailure)
        assertTrue(gate.isQuarantined("Wellness"))
        assertEquals(listOf("Wellness"), recorded)
        gate.run("Couchtisch") { }
        now += 120_001L
        assertTrue(runCatching { gate.run("Wellness") { } }.exceptionOrNull()
            is DeviceCommandGate.Deferred)
        assertEquals(0, gate.remaining("Wellness"))
        assertEquals(4, gate.remaining("Couchtisch"))
    }

    @Test fun jsonReplyRejectionQuarantinesWithoutRepeatingCallback() {
        val events = AtomicInteger()
        val gate = DeviceCommandGate(nowMs = { 20_000L }, maxCommandsPerMinute = 10,
            minSpacingMs = 0, onQuarantined = { events.incrementAndGet() })
        gate.run("Wellness", 2) { }
        gate.recordFirmwareRejection("Wellness", "Client quota exceeded")
        gate.recordFirmwareRejection("Wellness", "Client quota exceeded")
        assertEquals(1, events.get())
        assertEquals(0, gate.remaining("Wellness"))
        assertTrue(runCatching { gate.run("Wellness") { } }.exceptionOrNull()
            is DeviceCommandGate.Deferred)
    }

    @Test fun explicitRecoveryIsOncePerMinuteAndRequiresValidResponse() {
        var now = 30_000L
        var recovered = 0
        val gate = DeviceCommandGate(nowMs = { now }, maxCommandsPerMinute = 10,
            minSpacingMs = 0, onRecovered = { recovered++ })
        gate.recordFirmwareRejection("Wellness", "client quota exceeded")
        assertTrue(runCatching {
            gate.explicitRecoveryProbe("Wellness") { error("invalid get_prop") }
        }.isFailure)
        assertTrue(gate.isQuarantined("Wellness"))
        assertTrue(runCatching {
            gate.explicitRecoveryProbe("Wellness") { "on" }
        }.exceptionOrNull() is DeviceCommandGate.Deferred)
        now += 60_001L
        assertEquals("off", gate.explicitRecoveryProbe("Wellness") { "off" })
        assertFalse(gate.isQuarantined("Wellness"))
        assertEquals(1, recovered)
        gate.run("Wellness") { }
    }

    @Test fun incompleteProbeNeverClearsQuarantineWhenActionThrows() {
        var now = 10_000L
        val gate = DeviceCommandGate(nowMs = { now }, maxCommandsPerMinute = 10,
            minSpacingMs = 0)
        gate.markQuarantined("Wellness") // persistent state from prior process
        assertTrue(runCatching {
            gate.explicitRecoveryProbe("Wellness") { error("empty JSON array") }
        }.isFailure)
        assertTrue(gate.isQuarantined("Wellness"))
        now += 60_001L
        assertEquals("on", gate.explicitRecoveryProbe("Wellness") { "on" })
    }

    @Test fun successfulRecoveryReadDoesNotUnlockWhenDurableStorageFails() {
        var now = 25_000L
        var storeUsable = false
        var committed = 0
        val gate = DeviceCommandGate(
            nowMs = { now },
            minSpacingMs = 0,
            onRecovered = {
                if (!storeUsable) error("Quarantäne konnte nicht gespeichert werden")
                committed++
            }
        )
        gate.markQuarantined("Wellness")
        assertTrue(runCatching {
            gate.explicitRecoveryProbe("Wellness") { "on" }
        }.isFailure)
        assertTrue("Failed persistence must keep lamp blocked",
            gate.isQuarantined("Wellness"))
        assertTrue(runCatching {
            gate.run("Wellness") { error("must not run") }
        }.exceptionOrNull() is DeviceCommandGate.Deferred)
        // The failed read may have reached the bulb. Explicit retry only
        // after the cooldown, with a newly verified response.
        now += 60_001L
        storeUsable = true
        assertEquals("off", gate.explicitRecoveryProbe("Wellness") { "off" })
        assertFalse(gate.isQuarantined("Wellness"))
        assertEquals(1, committed)
    }

    @Test fun optionalBeatsReserveEnoughCapacityForStopAndRestore() {
        var now = 10_000L
        val gate = DeviceCommandGate(nowMs = { now }, maxCommandsPerMinute = 20,
            minSpacingMs = 0)
        gate.run("A", 3) { }
        while (gate.remaining("A") > 6) gate.run("A") { }
        assertEquals(6, gate.remaining("A"))
        gate.run("A", 3) { }
        assertEquals(3, gate.remaining("A"))
        assertEquals(20, gate.remaining("B"))
        now += 60_001L
        assertEquals(20, gate.remaining("A"))
    }

    @Test fun musicSocketCleanupCannotCloseUnderAnInFlightLanRequest() {
        val gate = DeviceCommandGate(nowMs = { 10_000L }, minSpacingMs = 0)
        val inFlight = CountDownLatch(1)
        val release = CountDownLatch(1)
        val startedCleanup = CountDownLatch(1)
        val actions = java.util.Collections.synchronizedList(mutableListOf<String>())
        val failures = AtomicReference<Throwable?>()
        val writer = thread(name = "fixture-same-lamp-command") {
            try {
                gate.run("lamp-a") {
                    inFlight.countDown()
                    check(release.await(3, TimeUnit.SECONDS)) { "Fixture command never released" }
                    actions.add("lan-finished")
                }
            } catch (ex: Throwable) { failures.set(ex) }
        }
        val cleanup = try {
            assertTrue(inFlight.await(2, TimeUnit.SECONDS))
            val cleaner = thread(name = "fixture-music-slot-cleanup") {
                try {
                    startedCleanup.countDown()
                    gate.closeSessionSlot("lamp-a") { actions.add("slot-closed") }
                } catch (ex: Throwable) { failures.set(ex) }
            }
            assertTrue(startedCleanup.await(2, TimeUnit.SECONDS))
            // A different lamp proceeds while the first lamp is in flight.
            gate.run("lamp-b") { actions.add("other-lamp") }
            assertFalse("Cleanup must wait for the in-flight request",
                actions.contains("slot-closed"))
            cleaner
        } finally {
            release.countDown()
        }
        writer.join(3_000)
        cleanup.join(3_000)
        assertFalse(writer.isAlive)
        assertFalse(cleanup.isAlive)
        assertNull(failures.get())
        assertTrue(actions.indexOf("lan-finished") < actions.indexOf("slot-closed"))
        assertEquals(1, gate.consumed("lamp-a"))
        assertEquals(1, gate.consumed("lamp-b"))
    }

    @Test fun quarantinedLampCanReleaseTcpSlotWithoutSendingAnyCommand() {
        val gate = DeviceCommandGate(nowMs = { 15_000L }, minSpacingMs = 0)
        gate.markQuarantined("lamp-a")
        var closed = 0
        gate.closeSessionSlot("lamp-a") { closed++ }
        gate.closeSessionSlot("lamp-a") { closed++ }
        assertEquals(2, closed)
        assertTrue(gate.isQuarantined("lamp-a"))
        assertEquals(0, gate.consumed("lamp-a"))
        assertEquals(0, gate.remaining("lamp-a"))
        assertTrue(runCatching { gate.run("lamp-a") { error("must not send") } }
            .exceptionOrNull() is DeviceCommandGate.Deferred)
    }

    @Test fun fourteenLampMixedSceneAndMusicOnlyAffectExplicitParticipants() {
        val gate = DeviceCommandGate(nowMs = { 100_000L }, minSpacingMs = 0)
        val all = (1..14).map { "fixture-lamp-$it" }
        val musicParticipants = all.take(4)
        val sceneParticipants = all.subList(4, 8)
        val quarantined = all[5]
        gate.markQuarantined(quarantined)
        val sent = mutableListOf<String>()
        // Four requested Music preflights, with independent slot cleanup.
        musicParticipants.forEach { ip ->
            gate.run(ip) { sent.add("music:$ip") }
            gate.closeSessionSlot(ip) { }
        }
        // A Scene cannot bypass the local quarantine of its second bulb;
        // failure of that bulb does not block other selected participants.
        sceneParticipants.forEach { ip ->
            runCatching { gate.run(ip) { sent.add("scene:$ip") } }
        }
        assertEquals(7, sent.size)
        assertFalse(sent.any { it.endsWith(quarantined) })
        assertTrue(gate.isQuarantined(quarantined))
        assertEquals(0, gate.consumed(quarantined))
        assertEquals(0, gate.consumed(all.last())) // nonparticipant untouched
        assertEquals(1, gate.consumed(all.first()))
        assertEquals(1, gate.consumed(sceneParticipants.first()))
    }

    @Test fun concurrentTrafficStillSharesThePerBulbQuota() {
        val gate = DeviceCommandGate(nowMs = { 10_000L }, maxCommandsPerMinute = 50,
            minSpacingMs = 0)
        val tasks = (0 until 12).map {
            Thread { repeat(20) { runCatching { gate.run("Wellness") { } } } }
        }
        tasks.forEach(Thread::start)
        tasks.forEach(Thread::join)
        assertEquals(50, gate.consumed("Wellness"))
    }
}
