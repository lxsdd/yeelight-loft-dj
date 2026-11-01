package io.github.lxsdd.yeelightloftdj

import org.junit.Assert.*
import org.junit.Test

class DeviceCommandGateTest {
    @Test fun locksOutRepeatedCommandsAndReleasesAfterRollingWindow() {
        var clock = 10000L
        val gate = DeviceCommandGate({clock}, maxCommandsPerMinute=3)
        gate.run("bulb-A",2) { }
        gate.run("bulb-A") { }
        assertEquals(3,gate.consumed("bulb-A"))
        val ex=runCatching { gate.run("bulb-A") {} }.exceptionOrNull()
        assertTrue(ex is DeviceCommandGate.Deferred)
        // Unrelated lamp must remain usable.
        gate.run("bulb-B",2) { }
        clock += 60_001
        gate.run("bulb-A") {}
        assertEquals(1,gate.consumed("bulb-A"))
    }

    @Test fun firmwareQuotaRejectsAndThrottlesOnlyOneBulb() {
        var clock=20000L
        val gate=DeviceCommandGate({clock},maxCommandsPerMinute=4)
        assertTrue(runCatching { gate.run("A") {
            throw IllegalStateException("stop_cf abgelehnt: client quota exceeded")
        }}.isFailure)
        val ex=runCatching { gate.run("A") {} }.exceptionOrNull()
        assertTrue(ex is DeviceCommandGate.Deferred)
        gate.run("B"){}
        clock+=60_001
        gate.run("A"){}
    }

    @Test fun rejectedPowerReplyAlsoTriggersCooldown() {
        var clock=20000L
        val gate=DeviceCommandGate({clock},maxCommandsPerMinute=4)
        gate.run("A",2){ }
        gate.recordFirmwareRejection("A","Client quota exceeded")
        assertTrue(runCatching { gate.run("A"){} }.exceptionOrNull()
            is DeviceCommandGate.Deferred)
        clock+=60_001
        gate.run("A"){}
    }

    @Test fun optionalBeatsReserveEnoughCapacityForStopAndRestore() {
        var now = 10_000L
        val gate = DeviceCommandGate({ now }, maxCommandsPerMinute = 20)
        gate.run("A", 3) { } // initial state capture / arm
        while (gate.remaining("A") > 6) {
            gate.run("A") { } // optional beat, last send leaves six slots
        }
        assertEquals(6, gate.remaining("A"))
        // Even when beat traffic stops, recovery can still use its own budget.
        gate.run("A", 3) { } // OFF + readback + final restore
        assertEquals(3, gate.remaining("A"))
        assertEquals(20, gate.remaining("B")) // independent lamp
        now += 60_001L
        assertEquals(20, gate.remaining("A"))
    }

    @Test fun firmwareQuotaMeansNoOptionalBeatHeadroom() {
        val gate = DeviceCommandGate({ 20_000L }, maxCommandsPerMinute = 20)
        gate.run("A") { }
        gate.recordFirmwareRejection("A", "client quota exceeded")
        assertEquals(0, gate.remaining("A"))
        assertEquals(20, gate.remaining("B"))
    }

    @Test fun concurrentRequestsCannotExceedPerBulbBudget() {
        val gate=DeviceCommandGate({10000L},maxCommandsPerMinute=50)
        val threads=(0 until 12).map {
            Thread {
                repeat(20) { runCatching { gate.run("same-bulb") {} } }
            }
        }
        threads.forEach(Thread::start)
        threads.forEach(Thread::join)
        assertEquals(50,gate.consumed("same-bulb"))
    }
}
