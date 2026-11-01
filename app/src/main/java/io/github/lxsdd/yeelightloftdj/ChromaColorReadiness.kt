package io.github.lxsdd.yeelightloftdj

/**
 * Cached, read-only SSDP capability assessment.
 * No model marketing, cloud claims, OEM strings or TCP music support can
 * authorize a Chroma socket. No networking code or mutable device state.
 */
enum class ChromaColorEvidence {
    SUPPORT_UNKNOWN,
    CHROMA_NOT_ADVERTISED,
    UDP_SESSION_NOT_ADVERTISED,
    RGB_SCENE_NOT_ADVERTISED,
    UDP_V1_RGB_CANDIDATE,
    UDP_V2_RGB_CANDIDATE,
    FIRMWARE_QUOTA_BLOCKED,
}

data class ChromaColorReadiness(
    val evidence: ChromaColorEvidence,
    val cachedOnly: Boolean = true,
    val hardwareVerified: Boolean = false,
    val maySendUdp: Boolean = false,
)

object ChromaColorReadinessReport {
    fun fromCachedSupport(
        methods: Set<String>,
        firmwareQuotaQuarantined: Boolean = false,
    ): ChromaColorReadiness {
        if (firmwareQuotaQuarantined)
            return ChromaColorReadiness(ChromaColorEvidence.FIRMWARE_QUOTA_BLOCKED)
        val scene = methods.any { it.trim().equals("set_scene", ignoreCase = true) }
        val evidence = when (ChromaUdpResearch.fromSupport(methods)) {
            ChromaUdpResearchEvidence.NO_SSDP_SUPPORT_EVIDENCE ->
                ChromaColorEvidence.SUPPORT_UNKNOWN
            ChromaUdpResearchEvidence.NOT_ADVERTISED ->
                ChromaColorEvidence.CHROMA_NOT_ADVERTISED
            ChromaUdpResearchEvidence.CHROMA_ADVERTISED_BUT_NO_SESSION_METHOD ->
                ChromaColorEvidence.UDP_SESSION_NOT_ADVERTISED
            ChromaUdpResearchEvidence.UDP_SESSION_V1_ADVERTISED ->
                if (scene) ChromaColorEvidence.UDP_V1_RGB_CANDIDATE
                else ChromaColorEvidence.RGB_SCENE_NOT_ADVERTISED
            ChromaUdpResearchEvidence.UDP_SESSION_V2_ADVERTISED ->
                if (scene) ChromaColorEvidence.UDP_V2_RGB_CANDIDATE
                else ChromaColorEvidence.RGB_SCENE_NOT_ADVERTISED
        }
        return ChromaColorReadiness(evidence)
    }
}
