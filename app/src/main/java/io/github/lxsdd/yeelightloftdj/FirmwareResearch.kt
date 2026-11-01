package io.github.lxsdd.yeelightloftdj

import java.util.Locale

/**
 * Research-only summary of published *model-specific* alternative-firmware work.
 *
 * The SSDP model is NOT a hardware/PCB identifier. Never use this catalog as
 * authorization to flash, unlock a bootloader or change the device transport.
 * It never contacts a device, logs an address, or changes an OEM installation.
 */
enum class FirmwareResearchRoute {
    NONE_VERIFIED,
    CONTROLLER_REPLACEMENT_REFERENCE,
    ESPHOME_CONFIGURATION_REFERENCE,
    THIRD_PARTY_STOCK_OTA_REFERENCE,
}

enum class FirmwareResearchFamily {
    CEILING1,
    COLOR4,
    STRIP6,
    MI_DESK_1S,
    OTHER_DOCUMENTED_ESP32,
    UNKNOWN,
}

data class FirmwareResearchProfile(
    val family: FirmwareResearchFamily,
    val route: FirmwareResearchRoute,
    val externalOtaFirmwareExactMatch: Boolean,
    /** This is always false until separate physical-model and rollback approval. */
    val safeToFlash: Boolean = false,
) {
    init {
        require(!safeToFlash) {
            "Research-only catalog must never authorize firmware writes"
        }
    }
}

/** No model alias may promote a mere discovery result to hardware confirmation. */
object FirmwareResearchCatalog {
    private fun normalize(model: String): String = model.trim().lowercase(Locale.ROOT)
        .removePrefix("yeelink.light.")
        .removePrefix("yeelight.light.")

    fun researchProfile(model: String, firmware: String? = null): FirmwareResearchProfile {
        val knownFirmware = firmware?.trim().orEmpty()
        val (family, route, publishedFirmware) = when (normalize(model)) {
            // Original controller described as MediaTek. ESP32-C6 only as
            // an invasive REPLACEMENT of the original controller.
            "ceiling1" -> Triple(
                FirmwareResearchFamily.CEILING1,
                FirmwareResearchRoute.CONTROLLER_REPLACEMENT_REFERENCE, null
            )
            // A 1S model label is not proof of MCU, revision or OTA protocol.
            "color4" -> Triple(
                FirmwareResearchFamily.COLOR4, FirmwareResearchRoute.NONE_VERIFIED, null
            )
            // An ESPHome config exists, but a matching product label alone
            // does not establish an exact PCB revision or reversible flashing.
            "strip6" -> Triple(
                FirmwareResearchFamily.STRIP6,
                FirmwareResearchRoute.ESPHOME_CONFIGURATION_REFERENCE, null
            )
            // Do not transfer generic "ESP32 Mi Desk Lamp 1S" hobby reports
            // to an unidentified desk lamp.
            "lamp1", "lamp2", "desklamp" -> Triple(
                FirmwareResearchFamily.MI_DESK_1S, FirmwareResearchRoute.NONE_VERIFIED, null
            )
            // Independent published OTA examples on *different* models:
            "lamp9" -> Triple(
                FirmwareResearchFamily.OTHER_DOCUMENTED_ESP32,
                FirmwareResearchRoute.THIRD_PARTY_STOCK_OTA_REFERENCE,
                "2.1.7_0031"
            )
            "ceiling10" -> Triple(
                FirmwareResearchFamily.OTHER_DOCUMENTED_ESP32,
                FirmwareResearchRoute.THIRD_PARTY_STOCK_OTA_REFERENCE,
                "2.0.6_0049"
            )
            "ceilb" -> Triple(
                FirmwareResearchFamily.OTHER_DOCUMENTED_ESP32,
                FirmwareResearchRoute.THIRD_PARTY_STOCK_OTA_REFERENCE,
                "2.1.7_0011"
            )
            "ceilc" -> Triple(
                FirmwareResearchFamily.OTHER_DOCUMENTED_ESP32,
                FirmwareResearchRoute.THIRD_PARTY_STOCK_OTA_REFERENCE,
                "2.0.6_0005"
            )
            else -> Triple(
                FirmwareResearchFamily.UNKNOWN, FirmwareResearchRoute.NONE_VERIFIED, null
            )
        }
        return FirmwareResearchProfile(
            family = family,
            route = route,
            externalOtaFirmwareExactMatch =
                publishedFirmware != null && publishedFirmware == knownFirmware,
        )
    }
}

/**
 * Passive evidence classifier for the ceiling1 offline investigation.
 * No network requests or power actions are performed.
 *
 * routerAssociatedNow must refer to a CURRENT association shown by the AP,
 * not a stale DHCP lease. A missing SSDP broadcast or functional BLE remote
 * alone tells us nothing about the Wi-Fi/cloud failure layer.
 */
data class OfflineEvidence(
    val routerAssociatedNow: Boolean? = null,
    val tcp55443Reachable: Boolean? = null,
    val cloudControlReachable: Boolean? = null,
    val explicitQuotaError: Boolean = false,
    val bluetoothRemoteResponds: Boolean? = null,
    val ssdpReplyReceived: Boolean? = null,
)

enum class OfflineFaultLayer {
    WIFI_ASSOCIATION_LOST,
    LOCAL_IP_OR_TCP_PATH,
    CLOUD_PATH,
    LAN_QUOTA,
    CONTRADICTORY_OBSERVATIONS,
    REACHABLE,
    INSUFFICIENT_EVIDENCE,
}

enum class OfflineNextCheck {
    READ_ROUTER_WIFI_EVENTS,
    CHECK_IP_ROUTE_AND_TCP,
    CHECK_WAN_DNS_AND_CLOUD,
    STOP_EXTRA_LAN_COMMANDS,
    RECHECK_OBSERVATION_TIMES,
    NO_CHANGE,
    COLLECT_ROUTER_ASSOCIATION,
}

data class OfflineAssessment(
    val layer: OfflineFaultLayer,
    val nextCheck: OfflineNextCheck,
)

object Ceiling1OfflineAnalysis {
    fun classify(observation: OfflineEvidence): OfflineAssessment {
        val associated = observation.routerAssociatedNow
        val tcp = observation.tcp55443Reachable
        val cloud = observation.cloudControlReachable
        val quota = observation.explicitQuotaError

        // Disagreeing snapshots are not a licence to claim "firmware defect".
        if ((associated == false && (tcp == true || cloud == true || quota)) ||
            (tcp == false && quota)) {
            return OfflineAssessment(
                OfflineFaultLayer.CONTRADICTORY_OBSERVATIONS,
                OfflineNextCheck.RECHECK_OBSERVATION_TIMES,
            )
        }
        if (associated == false) return OfflineAssessment(
            OfflineFaultLayer.WIFI_ASSOCIATION_LOST,
            OfflineNextCheck.READ_ROUTER_WIFI_EVENTS,
        )
        if (quota) return OfflineAssessment(
            OfflineFaultLayer.LAN_QUOTA,
            OfflineNextCheck.STOP_EXTRA_LAN_COMMANDS,
        )
        if (associated == true && tcp == false) return OfflineAssessment(
            OfflineFaultLayer.LOCAL_IP_OR_TCP_PATH,
            OfflineNextCheck.CHECK_IP_ROUTE_AND_TCP,
        )
        if (tcp == true && cloud == false) return OfflineAssessment(
            OfflineFaultLayer.CLOUD_PATH,
            OfflineNextCheck.CHECK_WAN_DNS_AND_CLOUD,
        )
        if (tcp == true && cloud == true) return OfflineAssessment(
            OfflineFaultLayer.REACHABLE,
            OfflineNextCheck.NO_CHANGE,
        )
        return OfflineAssessment(
            OfflineFaultLayer.INSUFFICIENT_EVIDENCE,
            OfflineNextCheck.COLLECT_ROUTER_ASSOCIATION,
        )
    }
}
