package io.github.lxsdd.yeelightloftdj

import org.junit.Assert.*
import org.junit.Test

class GoogleHomeCapturePlanTest {
    private fun device(id: String, ip: String, port: Int = 55443) =
        YeelightDevice(id, ip, port)
    private val a = device("a", "192.168.1.13")
    private val b = device("b", "192.168.1.14")

    @Test fun validatesKnownDistinctDeviceSelection() {
        assertEquals(listOf(a, b), GoogleHomeCapturePlan.validate(listOf(a, b), listOf(a, b), emptySet()))
    }

    @Test fun rejectsChangedEndpointAndUnknownOrDuplicateDevices() {
        assertThrows(IllegalArgumentException::class.java) {
            GoogleHomeCapturePlan.validate(listOf(a.copy(port = 77)), listOf(a), emptySet())
        }
        assertThrows(IllegalArgumentException::class.java) {
            GoogleHomeCapturePlan.validate(listOf(b), listOf(a), emptySet())
        }
        assertThrows(IllegalArgumentException::class.java) {
            GoogleHomeCapturePlan.validate(listOf(a, a), listOf(a), emptySet())
        }
        assertThrows(IllegalArgumentException::class.java) {
            GoogleHomeCapturePlan.validate(listOf(a, a.copy(id = "different")),
                listOf(a, a.copy(id = "different")), emptySet())
        }
    }

    @Test fun rejectsRecoveryBlockedAndNonlocalTargets() {
        assertThrows(IllegalArgumentException::class.java) {
            GoogleHomeCapturePlan.validate(listOf(a, b), listOf(a, b), setOf(b.ip))
        }
        val external = device("x", "8.8.8.8")
        assertThrows(IllegalArgumentException::class.java) {
            GoogleHomeCapturePlan.validate(listOf(external), listOf(external), emptySet())
        }
    }

    @Test fun rejectsEmptyOrOversizedBatches() {
        assertThrows(IllegalArgumentException::class.java) {
            GoogleHomeCapturePlan.validate(emptyList(), listOf(a), emptySet())
        }
        val many = (1..17).map { device("id$it", "192.168.5.$it") }
        assertThrows(IllegalArgumentException::class.java) {
            GoogleHomeCapturePlan.validate(many, many, emptySet())
        }
    }
}