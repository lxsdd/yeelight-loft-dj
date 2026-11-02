package io.github.lxsdd.yeelightloftdj

import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets

/**
 * In-memory ONLY protocol simulator for Yeelight's published Chroma UDP
 * protocol. No DatagramSocket, device discovery, Android service, DNS lookup,
 * TCP fallback, flash operations or production transport is reachable here.
 *
 * A device's SSDP support advertisement is NOT authenticated and is not
 * sufficient authorization for a real-network session. This simulates
 * application-layer validation; an actual sender must remain a later,
 * independently approved implementation.
 */
enum class ChromaPreviewState { NEW, AWAITING_TOKEN, ACTIVE, CLOSED }

enum class ChromaPreviewEvent {
    ACCEPTED_TOKEN, ACCEPTED_KEEPALIVE,
    IGNORED_FOREIGN_SENDER, IGNORED_INVALID_REPLY, IGNORED_UNEXPECTED_REPLY,
    CLOSED_ON_DEVICE_ERROR,
}

/**
 * toString deliberately redacts the wire payload (which may contain a token).
 * Only explicit test code should call forSimulatorOnly().
 */
class ChromaPreviewFrame internal constructor(private val wire: String) {
    fun forSimulatorOnly(): String = wire
    override fun toString(): String = "ChromaPreviewFrame(redacted)"
}

class ChromaUdpOfflineSession(
    private val expectedSenderIp: String,
    private val support: Set<String>,
) {
    /**
     * Synthetic endpoint identity only. Never exposed in snapshots, logs or
     * exceptions; it prevents two logical lanes from sharing one physical
     * target and silently doubling a per-device simulated command budget.
     */
    internal val offlineEndpointIdentity: String = expectedSenderIp.trim()

    init {
        // Canonical dotted-decimal IPv4 only. Never resolve a hostname or
        // infer an address through a network lookup. Reject octal-looking
        // aliases, IPv6, multicast, loopback, unspecified and broadcast.
        // Otherwise separate session objects could represent one endpoint
        // with different spellings and silently multiply the safety budget.
        val octets = offlineEndpointIdentity.split(".")
        require(octets.size == 4 && octets.all { part ->
            part.isNotEmpty() && part.length <= 3 &&
                part.all { it in '0'..'9' } &&
                (part.length == 1 || part[0] != '0') &&
                (part.toIntOrNull() ?: -1) in 0..255
        } && octets[0].toInt() in 1..223 && octets[0] != "127" &&
            offlineEndpointIdentity != "255.255.255.255") {
            "Canonical unicast IPv4 simulated endpoint required"
        }
    }

    private val evidence = ChromaUdpResearch.fromSupport(support)
    private val v2 = evidence == ChromaUdpResearchEvidence.UDP_SESSION_V2_ADVERTISED
    private val eligible = v2 ||
        evidence == ChromaUdpResearchEvidence.UDP_SESSION_V1_ADVERTISED
    private val expectedReplyMethod = if (v2) "udp_sess_token" else "udp_token"
    private val handshakeMethod = if (v2) "udp_sess_new" else "udp_new"
    private val keepAliveMethod = if (v2) "udp_sess_keep_alive" else "udp_keep_alive"

    var state: ChromaPreviewState = ChromaPreviewState.NEW
        private set

    private var nextId = 1
    private var handshakeId: Int? = null
    private var pendingKeepaliveId: Int? = null
    private var startedAtMs = -1L
    private var lastActivityMs = -1L
    private var lastObservedMs = -1L
    private var lastKeepaliveMs = -1L
    private var missedKeepalives = 0
    private var token: String? = null
    // Color frames do not require ACKs in the pinned OEM sample, but a
    // correlated *error* can still arrive. Retain at most one bounded set of
    // request IDs; never mistake a random/old reply for a quota rejection.
    // This is host fail-closed policy, not a proven Color4 firmware behavior.
    private val outstandingColorIds = linkedSetOf<Int>()

    val tokenHeld: Boolean get() = state == ChromaPreviewState.ACTIVE && token != null
    /** Exact cached SUPPORT method; an advertisement never proves real transport. */
    val simulatedSceneAdvertised: Boolean get() =
        eligible && support.any { it.trim().equals("set_scene", ignoreCase = true) }
    val ignoredOrMissedKeepalives: Int get() = missedKeepalives

    /** Creating a preview payload does not transmit it. Never retries. */
    fun begin(nowMs: Long): ChromaPreviewFrame? {
        if (!eligible || state != ChromaPreviewState.NEW || nowMs < 0) return null
        state = ChromaPreviewState.AWAITING_TOKEN
        startedAtMs = nowMs
        lastActivityMs = nowMs
        lastObservedMs = nowMs
        handshakeId = nextId++
        return frame(handshakeId!!, handshakeMethod, JSONArray())
    }

    /**
     * Caller supplies the originating IP from a fake transport and a monotonic
     * virtual clock. Both are checked, plus method, request ID and token.
     * No token/error content is returned to callers or placed in exceptions.
     */
    /** Backward-compatible simulator helper; new callers should pass the sender port. */
    fun acceptReply(senderIp: String, payload: String, nowMs: Long): ChromaPreviewEvent =
        acceptReply(senderIp, 55444, payload, nowMs)

    fun acceptReply(
        senderIp: String, senderPort: Int, payload: String, nowMs: Long,
    ): ChromaPreviewEvent {
        if (senderIp != offlineEndpointIdentity || senderPort != 55444)
            return ChromaPreviewEvent.IGNORED_FOREIGN_SENDER
        if (state != ChromaPreviewState.AWAITING_TOKEN &&
            state != ChromaPreviewState.ACTIVE) {
            return ChromaPreviewEvent.IGNORED_UNEXPECTED_REPLY
        }
        if (!advance(nowMs)) return ChromaPreviewEvent.IGNORED_UNEXPECTED_REPLY
        if (payload.toByteArray(StandardCharsets.UTF_8).size > 512 ||
            payload.any { it == '\u0000' }) {
            return ChromaPreviewEvent.IGNORED_INVALID_REPLY
        }
        val parsed = try { JSONObject(payload) } catch (_: Exception) {
            return ChromaPreviewEvent.IGNORED_INVALID_REPLY
        }
        // Device failures need not include params/method; fail CLOSED as soon
        // as a matching response ID carries an error, with no retry or token.
        // Never coerce "1" or 1.0 into the integer request ID.
        val rawId = parsed.opt("id")
        val replyId = if (rawId is Int) rawId else -1
        val expectedId = if (state == ChromaPreviewState.AWAITING_TOKEN)
            handshakeId else pendingKeepaliveId
        val correlatedColor = state == ChromaPreviewState.ACTIVE &&
            replyId in outstandingColorIds
        if (replyId <= 0 || (replyId != expectedId && !correlatedColor)) {
            return ChromaPreviewEvent.IGNORED_UNEXPECTED_REPLY
        }
        // A bare JSON error may be the only indication that the device
        // rejected a color frame. Do not depend on method/params or parse a
        // guessed OEM quota code. A sender/port/ID-correlated error is terminal.
        if (parsed.has("error")) {
            close()
            return ChromaPreviewEvent.CLOSED_ON_DEVICE_ERROR
        }
        // OEM set_scene is fire-and-forget. An optional color success reply
        // cannot mint/refresh a token, reset keepalive misses, or permit replay.
        // Keep the ID until terminal closure to catch delayed correlated errors.
        if (correlatedColor) return ChromaPreviewEvent.IGNORED_UNEXPECTED_REPLY
        if (!parsed.has("method") || !parsed.has("params") ||
            parsed.opt("params") !is JSONObject) {
            return ChromaPreviewEvent.IGNORED_INVALID_REPLY
        }
        val method = parsed.optString("method")
        if (method != expectedReplyMethod) return ChromaPreviewEvent.IGNORED_UNEXPECTED_REPLY
        val candidate = parsed.getJSONObject("params").opt("token")
        if (candidate !is String || !HEX_TOKEN.matches(candidate))
            return ChromaPreviewEvent.IGNORED_INVALID_REPLY
        return if (state == ChromaPreviewState.AWAITING_TOKEN) {
            token = candidate.lowercase()
            handshakeId = null
            pendingKeepaliveId = null
            missedKeepalives = 0
            lastActivityMs = nowMs
            lastKeepaliveMs = nowMs
            state = ChromaPreviewState.ACTIVE
            ChromaPreviewEvent.ACCEPTED_TOKEN
        } else {
            if (!candidate.equals(token, ignoreCase = true))
                return ChromaPreviewEvent.IGNORED_INVALID_REPLY
            pendingKeepaliveId = null
            missedKeepalives = 0
            lastActivityMs = nowMs
            ChromaPreviewEvent.ACCEPTED_KEEPALIVE
        }
    }

    /**
     * A conservative simulator watchdog, not a claimed firmware behavior.
     * If no matching keepalive acknowledgement is received for four
     * simulated keepalive intervals, locally closes and erases the token.
     */
    fun keepAlive(nowMs: Long): ChromaPreviewFrame? {
        if (!advance(nowMs) || state != ChromaPreviewState.ACTIVE) return null
        if (nowMs - lastKeepaliveMs < KEEPALIVE_MS) return null
        if (missedKeepalives >= MAX_MISSES - 1) {
            // Never issue another packet after the fourth unacknowledged
            // interval: close without attempting an automatic reconnect.
            close()
            return null
        }
        lastKeepaliveMs = nowMs
        missedKeepalives++
        pendingKeepaliveId = nextId++
        val params = JSONArray().put("keeplive_interval").put("10")
        return frame(pendingKeepaliveId!!, keepAliveMethod, params, token)
    }

    /** Only explicitly advertised OEM methods may be previewed. */
    fun previewColor(rgb: Int, brightness: Int, nowMs: Long): ChromaPreviewFrame? {
        if (!advance(nowMs) || state != ChromaPreviewState.ACTIVE) return null
        if (rgb !in 0..0xFFFFFF || brightness !in 1..100) return null
        if (!simulatedSceneAdvertised) return null
        // Standalone use of this simulator must also remain bounded even
        // without ACKs or a ChromaUdpOfflineFleet wrapper. No old IDs are
        // silently evicted: that could hide a delayed firmware error.
        if (outstandingColorIds.size >= MAX_TRACKED_COLOR_IDS) {
            close()
            return null
        }
        val params = JSONArray().put("color").put(rgb).put(brightness)
            .put(0).put("sudden")
        val id = nextId++
        val preview = frame(id, "set_scene", params, token)
        outstandingColorIds.add(id)
        return preview
    }

    /** Caller-monotonic virtual time, no scheduler/background behavior. */
    fun advance(nowMs: Long): Boolean {
        // Compare against *all* observed frames, not just the last ACK.
        if (nowMs < 0 || (lastObservedMs >= 0 && nowMs < lastObservedMs)) {
            close()
            return false
        }
        lastObservedMs = nowMs
        if (state == ChromaPreviewState.AWAITING_TOKEN &&
            nowMs - startedAtMs > HANDSHAKE_TIMEOUT_MS) {
            close()
            return false
        }
        if (state == ChromaPreviewState.ACTIVE &&
            nowMs - lastActivityMs > SESSION_SILENCE_MS) {
            close()
            return false
        }
        return state == ChromaPreviewState.ACTIVE ||
            state == ChromaPreviewState.AWAITING_TOKEN
    }

    fun close() {
        state = ChromaPreviewState.CLOSED
        token = null
        pendingKeepaliveId = null
        handshakeId = null
        outstandingColorIds.clear()
    }

    private fun frame(
        id: Int, method: String, params: JSONArray, sessionToken: String? = null,
    ): ChromaPreviewFrame {
        val data = JSONObject().put("id", id).put("method", method).put("params", params)
        if (sessionToken != null) data.put("token", sessionToken)
        return ChromaPreviewFrame(data.toString() + "\r\n")
    }

    companion object {
        private val HEX_TOKEN = Regex("^[0-9a-fA-F]{32}$")
        private const val HANDSHAKE_TIMEOUT_MS = 3_000L
        private const val KEEPALIVE_MS = 10_000L
        private const val SESSION_SILENCE_MS = 40_000L
        private const val MAX_MISSES = 4
        private const val MAX_TRACKED_COLOR_IDS = 45
    }
}
