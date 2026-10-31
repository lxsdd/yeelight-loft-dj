package io.github.lxsdd.yeelightloftdj

import org.junit.Assert.*
import org.junit.Test

class MiDeskLampDiagnosticsTest {
    @Test fun miIoMagicAndBoundedLengthRequired() {
        val valid = ByteArray(32) { 0 }
        valid[0] = 0x21
        valid[1] = 0x31
        valid[3] = 0x20
        assertTrue(MiDeskLampDiagnostics.validMiIoReply(valid, valid.size))
        assertFalse(MiDeskLampDiagnostics.validMiIoReply(valid, 10))
        valid[1] = 0
        assertFalse(MiDeskLampDiagnostics.validMiIoReply(valid, 32))
        valid[1] = 0x31
        valid[3] = 0x50
        assertFalse(MiDeskLampDiagnostics.validMiIoReply(valid, 32))
    }
}
