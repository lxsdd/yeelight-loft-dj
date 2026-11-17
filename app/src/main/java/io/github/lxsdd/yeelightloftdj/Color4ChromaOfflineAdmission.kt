package io.github.lxsdd.yeelightloftdj

/**
 * In-memory advisory decision only. NO LAN socket, IPC, Android service, frame,
 * credential, discovery, or production permission is created by this class.
 *
 * Crucial real-world case: one Color4 firmware 2.1.7_0039 returned no directed
 * SSDP SUPPORT even though an isolated V2 token + single visible color test
 * succeeded. Lack of an optional SSDP reply MUST NOT erase that local evidence;
 * nor can the single-device observation become fleet-wide eligibility.
 *
 * `observedFirmware` is supplied by an external trusted observation/operator;
 * this class does NOT query the bulb. The caller MUST bind `deviceKey` to the
 * same physical target as the witnessed test, and MUST keep real live transport
 * under its independent safety/ownership gate.
 */
class Color4ChromaOfflineAdmission(private val observations: Color4ChromaEvidenceGate) {
    enum class Basis {
        NONE, ADVERTISED_NOT_HARDWARE_VERIFIED,
        EXACT_DEVICE_V2_HARDWARE_OBSERVED,
        FIRMWARE_MISMATCH_OR_UNKNOWN,
    }
    data class Decision(val basis: Basis, val permitsOfflineV2Preview: Boolean) {
        /** Cannot ever authorize a real socket or production packet. */
        val grantsLiveControl: Boolean get() = false
        val demonstratesQuotaBypass: Boolean get() = false
    }

    /** No mutable cache; late device errors revoke evidence on every read. */
    fun assessV2(
        deviceKey: String,
        observedFirmware: String?,
        advertisedV2: Boolean,
    ): Decision {
        val witnessedV2 = observations.confirmedClosedVariant(deviceKey) ==
            Color4ChromaEvidenceGate.Variant.V2_UDP_SESS_NEW
        if (witnessedV2) {
            return if (observedFirmware == "2.1.7_0039" || observedFirmware == "v2.1.7_0039") {
                Decision(Basis.EXACT_DEVICE_V2_HARDWARE_OBSERVED, true)
            } else {
                Decision(Basis.FIRMWARE_MISMATCH_OR_UNKNOWN, false)
            }
        }
        return if (advertisedV2) Decision(Basis.ADVERTISED_NOT_HARDWARE_VERIFIED, false)
        else Decision(Basis.NONE, false)
    }
}
