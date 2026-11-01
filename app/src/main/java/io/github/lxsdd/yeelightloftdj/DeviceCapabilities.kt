package io.github.lxsdd.yeelightloftdj

/** Evidence comes from Yeelight SSDP SUPPORT, not marketing/model guesses. */
enum class CapabilityEvidence { CONFIRMED, UNVERIFIED, UNSUPPORTED }

data class DeviceCapability(
    val method: String,
    val label: String,
    val evidence: CapabilityEvidence,
)

/**
 * Direct TCP scanning confirms power/get_prop but does not advertise SUPPORT.
 * An empty methods set is therefore UNKNOWN, never proof of RGB/CT/CF.
 * Some old monochrome models can be ruled out even without SUPPORT.
 */
object DeviceCapabilities {
    private val features = listOf(
        "set_power" to "Ein/Aus",
        "set_bright" to "Helligkeit",
        "set_rgb" to "RGB-Farben",
        "set_ct_abx" to "Weißtemperatur",
        "set_hsv" to "HSV-Farben",
        "start_cf" to "Farbfluss",
        "stop_cf" to "Farbfluss stoppen",
        "set_scene" to "Direkte Szene",
    )

    fun evidence(device: YeelightDevice, method: String): CapabilityEvidence {
        if (device.methods.isNotEmpty())
            return if (method in device.methods) CapabilityEvidence.CONFIRMED
            else CapabilityEvidence.UNSUPPORTED

        // The documented 'mono' Yeelight device cannot change RGB/HSV/CT.
        // No other model name is treated as a substitute for SUPPORT evidence.
        if (device.model.lowercase() == "mono" &&
            method in setOf("set_rgb", "set_ct_abx", "set_hsv")
        ) return CapabilityEvidence.UNSUPPORTED
        return CapabilityEvidence.UNVERIFIED
    }

    fun capabilities(device: YeelightDevice): List<DeviceCapability> =
        features.map { (method, label) ->
            DeviceCapability(method, label, evidence(device, method))
        }

    fun canOffer(device: YeelightDevice, method: String, allowUnverified: Boolean): Boolean =
        when (evidence(device, method)) {
            CapabilityEvidence.CONFIRMED -> true
            CapabilityEvidence.UNVERIFIED -> allowUnverified
            CapabilityEvidence.UNSUPPORTED -> false
        }

    fun summary(device: YeelightDevice): String {
        if (device.methods.isEmpty()) return "Fähigkeiten nicht bestätigt"
        val confirmed = capabilities(device).filter {
            it.evidence == CapabilityEvidence.CONFIRMED &&
                it.method in setOf("set_rgb", "set_ct_abx", "start_cf", "set_bright")
        }.map { it.label }
        return if (confirmed.isEmpty()) "Eingeschränkte Steuerung"
        else confirmed.joinToString(" · ")
    }
}
