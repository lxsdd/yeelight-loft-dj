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
        // Exact methods only: reject lookalikes such as not_chroma.
        // udp_chroma_sess_new is a separate official Chroma marker.
        val known = methods.map { it.trim().lowercase(java.util.Locale.ROOT) }
            .filter { it.isNotEmpty() }.toSet()
        if (known.isEmpty()) return ChromaUdpResearchEvidence.NO_SSDP_SUPPORT_EVIDENCE
        val chroma = "chroma" in known || "udp_chroma_sess_new" in known
        if (!chroma) return ChromaUdpResearchEvidence.NOT_ADVERTISED
        return when {
            "udp_sess_new" in known -> ChromaUdpResearchEvidence.UDP_SESSION_V2_ADVERTISED
            "udp_new" in known -> ChromaUdpResearchEvidence.UDP_SESSION_V1_ADVERTISED
            else -> ChromaUdpResearchEvidence.CHROMA_ADVERTISED_BUT_NO_SESSION_METHOD
        }
    }

    /** Advertised is NOT a runtime-tested handshake or permission to send UDP. */
    fun mayOpenUdpSessionForResearch(evidence: ChromaUdpResearchEvidence): Boolean = false
}
