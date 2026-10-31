package io.github.lxsdd.yeelightloftdj

import org.junit.Assert.*
import org.junit.Test

class LampOrderTest {
    @Test fun moveFromEndToFirstPreservesOtherDevices() {
        assertEquals(listOf("D", "A", "B", "C"),
            LampOrder.relocate(listOf("A", "B", "C", "D"), "D", 0))
    }

    @Test fun sequentialPhysicalSelectionBuildsStableOrder() {
        var ids = listOf("A", "B", "C", "D")
        ids = LampOrder.relocate(ids, "C", 0)
        ids = LampOrder.relocate(ids, "A", 1)
        ids = LampOrder.relocate(ids, "D", 2)
        ids = LampOrder.relocate(ids, "B", 3)
        assertEquals(listOf("C", "A", "D", "B"), ids)
    }

    @Test fun adjacentMoveUpAndDownWorks() {
        assertEquals(listOf("B", "A", "C"),
            LampOrder.relocate(listOf("A", "B", "C"), "B", 0))
        assertEquals(listOf("A", "C", "B"),
            LampOrder.relocate(listOf("A", "B", "C"), "B", 2))
    }

    @Test fun invalidOperationsNeverDropOrDuplicateDevices() {
        val current = listOf("A", "B", "C")
        assertEquals(current, LampOrder.relocate(current, "unknown", 1))
        assertEquals(current, LampOrder.relocate(current, "B", -1))
        assertEquals(current, LampOrder.relocate(current, "B", 3))
        assertEquals(current, LampOrder.relocate(current, "B", 1))
    }

    @Test fun rejectDuplicateIdentifiers() {
        assertThrows(IllegalArgumentException::class.java) {
            LampOrder.relocate(listOf("A", "B", "A"), "B", 0)
        }
    }
}
