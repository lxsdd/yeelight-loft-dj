package io.github.lxsdd.yeelightloftdj

import org.junit.Assert.*
import org.junit.Test

class SceneRecoveryTest {
    private val rgb = LampSnapshot(true, 44, 1, 0xFF42A0, 3300, null, null)
    private val white = LampSnapshot(true, 70, 2, null, 3500, null, null)
    private val off = LampSnapshot(false, 30, 0, null, null, null, null)

    @Test fun restoresOriginalPowerModeAndBrightness() {
        assertEquals(listOf("set_power", "set_rgb", "set_bright"),
            SceneRecovery.commands(rgb).map { it.method })
        assertEquals(listOf("set_power", "set_ct_abx", "set_bright"),
            SceneRecovery.commands(white).map { it.method })
        assertEquals(listOf(LampCommands.power(false)), SceneRecovery.commands(off))
    }

    @Test fun blocksIrrecoverableOnColorMode() {
        assertFalse(SceneRecovery.supported(LampSnapshot(true, 50, 0, null, null, null, null)))
        assertFalse(SceneRecovery.supported(LampSnapshot(true, 50, 3, null, null, 50, null)))
        assertThrows(IllegalArgumentException::class.java) {
            SceneRecovery.commands(LampSnapshot(true, 50, 1, null, null, null, null))
        }
    }

    @Test fun recoveryJournalRoundTripsOnlyLocalSnapshots() {
        val original = mapOf("192.168.0.10" to rgb, "192.168.0.11" to off)
        val decoded = SceneRecovery.decode(SceneRecovery.encode(original))
        assertEquals(original, decoded)
        assertEquals(emptyMap<String, LampSnapshot>(), SceneRecovery.decode("untrusted"))
    }
}
