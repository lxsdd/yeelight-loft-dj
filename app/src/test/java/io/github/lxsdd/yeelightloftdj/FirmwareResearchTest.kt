package io.github.lxsdd.yeelightloftdj

import org.junit.Assert.*
import org.junit.Test

class FirmwareResearchCatalogTest {
    @Test fun knownCeiling1IsReplacementOnlyNotAnEspHomeFlashTarget() {
        val a = FirmwareResearchCatalog.researchProfile("yeelink.light.ceiling1")
        assertEquals(FirmwareResearchFamily.CEILING1, a.family)
        assertEquals(FirmwareResearchRoute.CONTROLLER_REPLACEMENT_REFERENCE, a.route)
        assertFalse(a.externalOtaFirmwareExactMatch)
        assertFalse(a.safeToFlash)
    }

    @Test fun allKnownColorsKeepUnverifiedFirmwareEvenWhenVersionSupplied() {
        for (model in listOf("color4", "YEELINK.LIGHT.COLOR4", " color4 ")) {
            val a = FirmwareResearchCatalog.researchProfile(model, "2.1.7_0031")
            assertEquals(FirmwareResearchFamily.COLOR4, a.family)
            assertEquals(FirmwareResearchRoute.NONE_VERIFIED, a.route)
            assertFalse(a.externalOtaFirmwareExactMatch)
            assertFalse(a.safeToFlash)
        }
    }

    @Test fun strip6ConfigIsOnlyAReferenceNotBoardOrFlashApproval() {
        val a = FirmwareResearchCatalog.researchProfile("yeelink.light.strip6")
        assertEquals(FirmwareResearchFamily.STRIP6, a.family)
        assertEquals(FirmwareResearchRoute.ESPHOME_CONFIGURATION_REFERENCE, a.route)
        assertFalse(a.safeToFlash)
    }

    @Test fun publishedOtaResearchNeedsExactModelAndFirmwareVersion() {
        val exact = FirmwareResearchCatalog.researchProfile("lamp9", "2.1.7_0031")
        assertTrue(exact.externalOtaFirmwareExactMatch)
        assertFalse(exact.safeToFlash)
        assertFalse(FirmwareResearchCatalog.researchProfile("lamp9").externalOtaFirmwareExactMatch)
        assertFalse(FirmwareResearchCatalog.researchProfile("lamp9", "2.1.7_0030")
            .externalOtaFirmwareExactMatch)
        assertFalse(FirmwareResearchCatalog.researchProfile("color4", "2.1.7_0031")
            .externalOtaFirmwareExactMatch)
        assertTrue(FirmwareResearchCatalog.researchProfile("ceiling10", "2.0.6_0049")
            .externalOtaFirmwareExactMatch)
        assertTrue(FirmwareResearchCatalog.researchProfile("ceilb", "2.1.7_0011")
            .externalOtaFirmwareExactMatch)
        assertTrue(FirmwareResearchCatalog.researchProfile("ceilc", "2.0.6_0005")
            .externalOtaFirmwareExactMatch)
    }

    @Test fun modelSubstringsCannotInventCompatibility() {
        for (model in listOf("color4_debug", "xcolor4", "ceiling100", "strip6a", "", "unknown")) {
            val a = FirmwareResearchCatalog.researchProfile(model)
            assertEquals(FirmwareResearchFamily.UNKNOWN, a.family)
            assertEquals(FirmwareResearchRoute.NONE_VERIFIED, a.route)
            assertFalse(a.safeToFlash)
        }
    }

    @Test fun researchProfileProhibitsAccidentalFlashApprovalEvenIfCalledDirectly() {
        try {
            FirmwareResearchProfile(
                FirmwareResearchFamily.OTHER_DOCUMENTED_ESP32,
                FirmwareResearchRoute.THIRD_PARTY_STOCK_OTA_REFERENCE,
                true, safeToFlash = true
            )
            fail("Must reject hardware write authorization")
        } catch (_: IllegalArgumentException) {
            // correct
        }
    }
}

class Ceiling1OfflineAnalysisTest {
    @Test fun physicalBluetoothRemoteDoesNotImplyWifiAvailability() {
        val a = Ceiling1OfflineAnalysis.classify(
            OfflineEvidence(routerAssociatedNow = false, bluetoothRemoteResponds = true)
        )
        assertEquals(OfflineFaultLayer.WIFI_ASSOCIATION_LOST, a.layer)
        assertEquals(OfflineNextCheck.READ_ROUTER_WIFI_EVENTS, a.nextCheck)
    }

    @Test fun activeWifiButNoTcpMeansLocalPathNotCloudAssumption() {
        val a = Ceiling1OfflineAnalysis.classify(
            OfflineEvidence(routerAssociatedNow = true, tcp55443Reachable = false)
        )
        assertEquals(OfflineFaultLayer.LOCAL_IP_OR_TCP_PATH, a.layer)
    }

    @Test fun localTcpOnlineAndCloudUnavailableMeansCloudPath() {
        val a = Ceiling1OfflineAnalysis.classify(
            OfflineEvidence(tcp55443Reachable = true, cloudControlReachable = false)
        )
        assertEquals(OfflineFaultLayer.CLOUD_PATH, a.layer)
    }

    @Test fun explicitQuotaIsNotWifiOrCloudOffline() {
        val a = Ceiling1OfflineAnalysis.classify(
            OfflineEvidence(routerAssociatedNow = true, tcp55443Reachable = true,
                explicitQuotaError = true, cloudControlReachable = false)
        )
        assertEquals(OfflineFaultLayer.LAN_QUOTA, a.layer)
        assertEquals(OfflineNextCheck.STOP_EXTRA_LAN_COMMANDS, a.nextCheck)
    }

    @Test fun missingSsdpAndWorkingBleAloneCannotClaimOffline() {
        val a = Ceiling1OfflineAnalysis.classify(
            OfflineEvidence(ssdpReplyReceived = false, bluetoothRemoteResponds = true)
        )
        assertEquals(OfflineFaultLayer.INSUFFICIENT_EVIDENCE, a.layer)
    }

    @Test fun staleOrConflictingRouterAndTcpObservationsRejected() {
        val a = Ceiling1OfflineAnalysis.classify(
            OfflineEvidence(routerAssociatedNow = false, tcp55443Reachable = true)
        )
        assertEquals(OfflineFaultLayer.CONTRADICTORY_OBSERVATIONS, a.layer)
        val b = Ceiling1OfflineAnalysis.classify(
            OfflineEvidence(routerAssociatedNow = true, tcp55443Reachable = false,
                explicitQuotaError = true)
        )
        assertEquals(OfflineFaultLayer.CONTRADICTORY_OBSERVATIONS, b.layer)
    }

    @Test fun positiveTcpAndCloudAreReachableEvenWithoutSsdp() {
        val a = Ceiling1OfflineAnalysis.classify(
            OfflineEvidence(routerAssociatedNow = true, tcp55443Reachable = true,
                cloudControlReachable = true, ssdpReplyReceived = false)
        )
        assertEquals(OfflineFaultLayer.REACHABLE, a.layer)
    }

    @Test fun cloudFailureWithoutLocalEvidenceIsUndetermined() {
        val a = Ceiling1OfflineAnalysis.classify(
            OfflineEvidence(cloudControlReachable = false, bluetoothRemoteResponds = true)
        )
        assertEquals(OfflineFaultLayer.INSUFFICIENT_EVIDENCE, a.layer)
    }
}
