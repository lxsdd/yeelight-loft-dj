package io.github.lxsdd.yeelightloftdj

import org.junit.Assert.*
import org.junit.Test

class ChromaUdpResearchTest {
    @Test fun noDiscoverySupportDoesNotInferChromaFromModelOrFirmware() {
        assertEquals(ChromaUdpResearchEvidence.NO_SSDP_SUPPORT_EVIDENCE,
            ChromaUdpResearch.fromSupport(emptySet()))
        assertEquals(ChromaUdpResearchEvidence.NOT_ADVERTISED,
            ChromaUdpResearch.fromSupport(setOf("set_music", "set_rgb", "start_cf")))
    }

    @Test fun sessionMethodWithoutChromaCapabilityIsNotEnough() {
        assertEquals(ChromaUdpResearchEvidence.NOT_ADVERTISED,
            ChromaUdpResearch.fromSupport(setOf("udp_new", "udp_sess_new")))
    }

    @Test fun chromaWithoutKnownSessionMethodIsNotEnough() {
        assertEquals(ChromaUdpResearchEvidence.CHROMA_ADVERTISED_BUT_NO_SESSION_METHOD,
            ChromaUdpResearch.fromSupport(setOf("udp_chroma_sess_new", "set_rgb")))
    }

    @Test fun confirmedAdvertisementChoosesNewerVersionWhenBothAppear() {
        assertEquals(ChromaUdpResearchEvidence.UDP_SESSION_V2_ADVERTISED,
            ChromaUdpResearch.fromSupport(setOf("set_rgb", "udp_sess_new", "udp_new",
                "udp_chroma_sess_new")))
        assertEquals(ChromaUdpResearchEvidence.UDP_SESSION_V1_ADVERTISED,
            ChromaUdpResearch.fromSupport(setOf("udp_new", "chroma")))
    }

    @Test fun noResearchEvidenceAllowsNetworkWrites() {
        for (e in ChromaUdpResearchEvidence.entries) {
            assertFalse(ChromaUdpResearch.mayOpenUdpSessionForResearch(e))
        }
    }
}
