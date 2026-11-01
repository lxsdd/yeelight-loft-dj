package io.github.lxsdd.yeelightloftdj

import org.junit.Assert.*
import org.junit.Test

class ChromaColorReadinessTest {
    private fun status(vararg methods: String): ChromaColorEvidence =
        ChromaColorReadinessReport.fromCachedSupport(methods.toSet()).evidence

    @Test fun missingSupportIsUnknownNotHardwareRefutation() {
        assertEquals(ChromaColorEvidence.SUPPORT_UNKNOWN, status())
        assertEquals(ChromaColorEvidence.SUPPORT_UNKNOWN, status("  "))
    }

    @Test fun tcpMusicAndRgbDoNotGrantChromaCapability() {
        assertEquals(ChromaColorEvidence.CHROMA_NOT_ADVERTISED,
            status("set_music", "set_rgb", "set_scene", "udp_sess_new"))
    }

    @Test fun substringSpoofAndIncompleteSessionAreRejected() {
        assertEquals(ChromaColorEvidence.CHROMA_NOT_ADVERTISED,
            status("not_chroma", "udp_new", "set_scene"))
        assertEquals(ChromaColorEvidence.UDP_SESSION_NOT_ADVERTISED,
            status("chroma", "set_scene"))
    }

    @Test fun v1AndV2NeedChromaSessionAndRgbSceneTogether() {
        assertEquals(ChromaColorEvidence.UDP_V1_RGB_CANDIDATE,
            status("chroma", "udp_new", "set_scene"))
        assertEquals(ChromaColorEvidence.UDP_V2_RGB_CANDIDATE,
            status("udp_chroma_sess_new", "udp_sess_new", "set_scene"))
        assertEquals(ChromaColorEvidence.UDP_V2_RGB_CANDIDATE,
            status("chroma", "udp_new", "udp_sess_new", "set_scene"))
    }

    @Test fun sessionWithoutSceneDoesNotPromiseRgbFrames() {
        assertEquals(ChromaColorEvidence.RGB_SCENE_NOT_ADVERTISED,
            status("chroma", "udp_new"))
        assertEquals(ChromaColorEvidence.RGB_SCENE_NOT_ADVERTISED,
            status("chroma", "udp_sess_new", "bg_set_rgb"))
    }

    @Test fun exactTokensAcceptCaseAndWhitespaceOnly() {
        assertEquals(ChromaColorEvidence.UDP_V2_RGB_CANDIDATE,
            status("CHROMA ", " UDP_SESS_NEW", " SET_SCENE "))
        assertEquals(ChromaColorEvidence.RGB_SCENE_NOT_ADVERTISED,
            status("chroma", "udp_new", "set_scene_fake"))
    }

    @Test fun firmwareQuotaTakesPrecedenceOverCachedSupport() {
        val result = ChromaColorReadinessReport.fromCachedSupport(
            setOf("chroma", "udp_sess_new", "set_scene"), true)
        assertEquals(ChromaColorEvidence.FIRMWARE_QUOTA_BLOCKED, result.evidence)
        assertFalse(result.hardwareVerified)
        assertFalse(result.maySendUdp)
    }

    @Test fun noOutcomeEverAuthorizesSocketOrClaimsHardwareEvidence() {
        val combinations = listOf(
            emptySet(), setOf("set_music"), setOf("chroma"),
            setOf("chroma", "udp_new"), setOf("chroma", "udp_new", "set_scene"),
            setOf("chroma", "udp_sess_new", "set_scene"))
        for (capabilities in combinations) for (blocked in listOf(false, true)) {
            val result = ChromaColorReadinessReport.fromCachedSupport(capabilities, blocked)
            assertTrue(result.cachedOnly)
            assertFalse(result.hardwareVerified)
            assertFalse(result.maySendUdp)
        }
    }
}
