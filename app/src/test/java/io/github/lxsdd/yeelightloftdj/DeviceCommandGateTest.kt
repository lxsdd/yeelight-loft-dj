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
