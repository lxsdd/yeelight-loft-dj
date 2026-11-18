package io.github.lxsdd.yeelightloftdj

/**
 * Research-only capability gate for Yeelight's publicly released Chroma UDP
 * connector. Never starts a socket, negotiates a token, or sends colors.
 *
 * The manufacturer's C++ connector filters on an advertised "chroma"
 * capability and selects between udp_new and udp_sess_new. We additionally
 * require explicit session-method evidence; the C++ implementation's loose
 * substring acceptance must not become a guess based on model labels.
 */
enum class ChromaUdpResearchEvidence {
    NO_SSDP_SUPPORT_EVIDENCE,
    NOT_ADVERTISED,
    CHROMA_ADVERTISED_BUT_NO_SESSION_METHOD,
    UDP_SESSION_V1_ADVERTISED,
    UDP_SESSION_V2_ADVERTISED,
}

object ChromaUdpResearch {
    fun fromSupport(methods: Set<String>): ChromaUdpResearchEvidence {
        if (methods.isEmpty()) return ChromaUdpResearchEvidence.NO_SSDP_SUPPORT_EVIDENCE
        // "chroma" appears in different provider implementations under
        // differing method names. It must come from this device's own
        // authenticated-by-source SSDP discovery, never a marketing feature.
        val chroma = methods.any { it.contains("chroma", ignoreCase = true) }
        if (!chroma) return ChromaUdpResearchEvidence.NOT_ADVERTISED
        return when {
            "udp_sess_new" in methods -> ChromaUdpResearchEvidence.UDP_SESSION_V2_ADVERTISED
            "udp_new" in methods -> ChromaUdpResearchEvidence.UDP_SESSION_V1_ADVERTISED
            else -> ChromaUdpResearchEvidence.CHROMA_ADVERTISED_BUT_NO_SESSION_METHOD
        }
    }

    /** Advertised is NOT a runtime-tested handshake or permission to send UDP. */
    fun mayOpenUdpSessionForResearch(evidence: ChromaUdpResearchEvidence): Boolean = false
}
