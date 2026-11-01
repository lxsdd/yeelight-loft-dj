package io.github.lxsdd.yeelightloftdj

import org.junit.Assert.*
import org.junit.Test

class DeviceCapabilitiesTest {
    @Test fun color4UsesReportedMethodsNotModelGuess() {
        val lamp = YeelightDevice("a", "192.168.1.10", model = "color4",
            methods = setOf("get_prop", "set_power", "set_bright",
                "set_rgb", "start_cf", "stop_cf"))
        assertEquals(CapabilityEvidence.CONFIRMED,
            DeviceCapabilities.evidence(lamp, "set_rgb"))
        assertEquals(CapabilityEvidence.UNSUPPORTED,
            DeviceCapabilities.evidence(lamp, "set_ct_abx"))
        assertTrue(DeviceCapabilities.canOffer(lamp, "start_cf", false))
        assertFalse(DeviceCapabilities.canOffer(lamp, "set_ct_abx", true))
    }

    @Test fun tcpOnlyIsUnverifiedNotUniversalSupport() {
        val device = YeelightDevice("ip-1", "192.168.1.10")
        assertEquals(CapabilityEvidence.UNVERIFIED,
            DeviceCapabilities.evidence(device, "set_rgb"))
        assertFalse(DeviceCapabilities.canOffer(device, "set_rgb", false))
        assertTrue(DeviceCapabilities.canOffer(device, "set_rgb", true))
        assertEquals("Fähigkeiten nicht bestätigt", DeviceCapabilities.summary(device))
    }

    @Test fun advertisedWhiteOnlyBulbsDoNotGetRgbOrFlowControls() {
        val mono = YeelightDevice("mono", "192.168.1.11", model = "mono",
            methods = setOf("set_power", "set_bright", "get_prop"))
        assertFalse(DeviceCapabilities.canOffer(mono, "set_rgb", true))
        assertFalse(DeviceCapabilities.canOffer(mono, "start_cf", true))
        assertTrue(DeviceCapabilities.canOffer(mono, "set_bright", false))
    }

    @Test fun fallbackMonoModelCannotOfferBlindRgb() {
        val mono = YeelightDevice("mono", "192.168.1.11", model = "mono")
        assertEquals(CapabilityEvidence.UNSUPPORTED,
            DeviceCapabilities.evidence(mono, "set_rgb"))
        assertEquals(CapabilityEvidence.UNVERIFIED,
            DeviceCapabilities.evidence(mono, "set_bright"))
    }

    @Test fun modelNameAloneDoesNotConfirmColor4Features() {
        val lamp = YeelightDevice("color", "192.168.1.12", model = "color4")
        assertEquals(CapabilityEvidence.UNVERIFIED,
            DeviceCapabilities.evidence(lamp, "start_cf"))
    }
}
