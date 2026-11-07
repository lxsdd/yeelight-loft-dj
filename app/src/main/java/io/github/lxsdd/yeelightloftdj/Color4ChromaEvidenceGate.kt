package io.github.lxsdd.yeelightloftdj

/**
 * Offline-only, per-device, one-attempt evidence gate for hardware Chroma tests.
 *
 * Has NO network, timers, file writes, discovery, Android UI, or device-control
 * dependencies. Never stores or logs device IP, raw reply payload, or a token.
 * The caller must validate sender-IP/port and integer JSON-ID before passing
 * an observed reply here; this model does NOT authenticate network packets.
 * One confirmed bulb/transport is never treated as proof for another bulb or
 * the experimental udp_chroma_sess_new -> color path.
 *
 * The real Windows experiment can report an experimental token-shaped reply
 * and a *separate* working udp_sess_new + set_scene path. Those are NOT the
 * same test: do not merge them into one purported full Chroma session.
 */
class Color4ChromaEvidenceGate {
    enum class Variant { V1_UDP_NEW, V2_UDP_SESS_NEW, EXPERIMENTAL_CHROMA_METHOD }
    enum class Evidence {
        UNTESTED, AWAITING_HANDSHAKE, HANDSHAKE_ONLY,
        EXPERIMENTAL_METHOD_ONLY, COLOR_SENT_UNCONFIRMED,
        COLOR_VISUALLY_CONFIRMED, REJECTED_OR_UNCERTAIN
    }
    data class Attempt(val deviceKey: String, val generation: Long, val variant: Variant)
    private data class Lane(
        var attempt: Attempt? = null,
        var evidence: Evidence = Evidence.UNTESTED,
        var sessionClosed: Boolean = true,
        var operatorResetRequired: Boolean = false,
    )
    private val lanes = mutableMapOf<String, Lane>()
    private var nextGeneration = 1L
    private val hexToken = Regex("^[0-9a-fA-F]{32}$")
    private val opaqueKey = Regex("^[a-zA-Z][a-zA-Z0-9_-]{0,31}$")

    @Synchronized
    fun beginExplicitOneShot(deviceKey: String, variant: Variant): Attempt? {
        if (!opaqueKey.matches(deviceKey) || nextGeneration == Long.MAX_VALUE) return null
        val lane = lanes.getOrPut(deviceKey) { Lane() }
        if (!lane.sessionClosed || lane.operatorResetRequired || lane.attempt != null) return null
        return Attempt(deviceKey, nextGeneration++, variant).also {
            lane.attempt = it
            lane.sessionClosed = false
            lane.evidence = Evidence.AWAITING_HANDSHAKE
        }
    }

    /** Exact method/ID/token-form validation; raw token NEVER retained. */
    @Synchronized
    fun onTokenReply(
        attempt: Attempt, requestId: Int, replyId: Int,
        replyMethod: String, opaqueToken: String,
    ): Boolean {
        val lane = active(attempt) ?: return false
        if (lane.evidence != Evidence.AWAITING_HANDSHAKE) return false
        val method = when (attempt.variant) {
            Variant.V1_UDP_NEW -> "udp_token"
            Variant.V2_UDP_SESS_NEW -> "udp_sess_token"
            // Response name for this OEM method remains unresolved. A valid
            // experimental reply is NOT an endorsement to send set_scene.
            Variant.EXPERIMENTAL_CHROMA_METHOD -> null
        }
        if (requestId != 1 || replyId != requestId || !hexToken.matches(opaqueToken) ||
            (method == null && replyMethod !in setOf(
                "udp_sess_token", "udp_chroma_sess_token", "udp_token"
            )) || (method != null && replyMethod != method)) return false
        lane.evidence = if (method == null) Evidence.EXPERIMENTAL_METHOD_ONLY
            else Evidence.HANDSHAKE_ONLY
        return true
    }

    /** Records an explicitly issued *single* frame; NEVER sends it. */
    @Synchronized
    fun recordOneColorFrame(attempt: Attempt, frameId: Int): Boolean {
        val lane = active(attempt) ?: return false
        if (attempt.variant == Variant.EXPERIMENTAL_CHROMA_METHOD ||
            frameId != 2 || lane.evidence != Evidence.HANDSHAKE_ONLY) return false
        lane.evidence = Evidence.COLOR_SENT_UNCONFIRMED
        return true
    }

    /** A color-frame success ACK or silence is NOT evidence of visible color. */
    @Synchronized
    fun confirmVisibleColorByOperator(attempt: Attempt, explicitlyObserved: Boolean): Boolean {
        val lane = active(attempt) ?: return false
        if (!explicitlyObserved || lane.evidence != Evidence.COLOR_SENT_UNCONFIRMED) return false
        lane.evidence = Evidence.COLOR_VISUALLY_CONFIRMED
        return true
    }

    /**
     * Only a sender/port/ID-correlated actual device ERROR may revoke a
     * completed one-shot observation, even after socket closure. A late
     * handshake error stays meaningful after a color frame was sent.
     * The transport caller validates origin and JSON integer ID; an ACK,
     * timeout callback or silence must NOT call this method.
     */
    @Synchronized
    fun onCorrelatedDeviceError(attempt: Attempt, actualRequestId: Int): Boolean {
        val lane = matching(attempt) ?: return false
        if (lane.evidence == Evidence.REJECTED_OR_UNCERTAIN ||
            lane.evidence == Evidence.UNTESTED) return false
        // The handshake request ID 1 was issued by beginExplicitOneShot.
        // Frame ID 2 was issued only after recordOneColorFrame succeeded.
        if (actualRequestId != 1 && actualRequestId != 2) return false
        if (actualRequestId == 2 && lane.evidence != Evidence.COLOR_SENT_UNCONFIRMED &&
            lane.evidence != Evidence.COLOR_VISUALLY_CONFIRMED) return false
        lane.evidence = Evidence.REJECTED_OR_UNCERTAIN
        lane.sessionClosed = true
        lane.operatorResetRequired = true
        return true
    }

    /**
     * A local ambiguous-send or timeout can only be terminal while the
     * corresponding request is still unresolved. Never downgrade a real
     * visible PASS because an obsolete timeout callback fired later.
     * A color packet is fire-and-forget: absence of a success ACK alone
     * is not a color-command timeout or device quota evidence.
     */
    @Synchronized
    fun onAmbiguousSendOrTimeout(attempt: Attempt, actualRequestId: Int): Boolean {
        val lane = active(attempt) ?: return false
        if (actualRequestId == 1 && lane.evidence != Evidence.AWAITING_HANDSHAKE) return false
        if (actualRequestId == 2 && lane.evidence != Evidence.COLOR_SENT_UNCONFIRMED) return false
        if (actualRequestId != 1 && actualRequestId != 2) return false
        lane.evidence = Evidence.REJECTED_OR_UNCERTAIN
        lane.sessionClosed = true
        lane.operatorResetRequired = true
        return true
    }

    /** Closing an unobserved color frame is ambiguous, never a PASS. */
    @Synchronized
    fun close(attempt: Attempt): Boolean {
        val lane = active(attempt) ?: return false
        if (lane.evidence == Evidence.AWAITING_HANDSHAKE ||
            lane.evidence == Evidence.COLOR_SENT_UNCONFIRMED) {
            lane.evidence = Evidence.REJECTED_OR_UNCERTAIN
        }
        lane.sessionClosed = true
        lane.operatorResetRequired = true // explicitly one-shot even after PASS
        return true
    }

    @Synchronized
    fun evidence(deviceKey: String): Evidence = lanes[deviceKey]?.evidence ?: Evidence.UNTESTED

    /**
     * A successful *closed* hardware test, not a session token or live send permit.
     * The exact per-device V2 variant must survive until operator reset; neither
     * SSDP absence nor an unrelated lamp's successful test may change it.
     * A delayed correlated device error revokes this evidence automatically.
     */
    @Synchronized
    fun confirmedClosedVariant(deviceKey: String): Variant? {
        val lane = lanes[deviceKey] ?: return null
        if (!lane.sessionClosed || lane.evidence != Evidence.COLOR_VISUALLY_CONFIRMED) return null
        val variant = lane.attempt?.variant ?: return null
        return if (variant == Variant.EXPERIMENTAL_CHROMA_METHOD) null else variant
    }

    /** Explicit local reset never turns evidence into unconditional device support. */
    @Synchronized
    fun clearOnlyByOperator(deviceKey: String): Boolean {
        val lane = lanes[deviceKey] ?: return false
        if (!lane.sessionClosed || !lane.operatorResetRequired) return false
        lane.attempt = null
        lane.evidence = Evidence.UNTESTED
        lane.operatorResetRequired = false
        return true
    }

    private fun matching(attempt: Attempt): Lane? {
        val lane = lanes[attempt.deviceKey] ?: return null
        return if (lane.attempt == attempt) lane else null
    }

    private fun active(attempt: Attempt): Lane? {
        val lane = matching(attempt) ?: return null
        return if (lane.sessionClosed) null else lane
    }
}
