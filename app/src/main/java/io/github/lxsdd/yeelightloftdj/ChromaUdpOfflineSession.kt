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
    private var lastKeepaliveMs = -1L
    private var missedKeepalives = 0
    private var token: String? = null

    val tokenHeld: Boolean get() = state == ChromaPreviewState.ACTIVE && token != null
    val ignoredOrMissedKeepalives: Int get() = missedKeepalives

    /** Creating a preview payload does not transmit it. Never retries. */
    fun begin(nowMs: Long): ChromaPreviewFrame? {
        if (!eligible || state != ChromaPreviewState.NEW || nowMs < 0) return null
        state = ChromaPreviewState.AWAITING_TOKEN
        startedAtMs = nowMs
        lastActivityMs = nowMs
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
        if (senderIp != expectedSenderIp || senderPort != 55444)
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
        val replyId = parsed.optInt("id", -1)
        val expectedId = if (state == ChromaPreviewState.AWAITING_TOKEN)
            handshakeId else pendingKeepaliveId
        if (replyId <= 0 || replyId != expectedId) {
            return ChromaPreviewEvent.IGNORED_UNEXPECTED_REPLY
        }
        if (parsed.has("error")) {
            close()
            return ChromaPreviewEvent.CLOSED_ON_DEVICE_ERROR
        }
        if (!parsed.has("method") || !parsed.has("params") ||
            parsed.opt("params") !is JSONObject) {
            return ChromaPreviewEvent.IGNORED_INVALID_REPLY
        }
        val method = parsed.optString("method")
        if (method != expectedReplyMethod) return ChromaPreviewEvent.IGNORED_UNEXPECTED_REPLY
        val candidate = parsed.getJSONObject("params").optString("token", "")
        if (!HEX_TOKEN.matches(candidate)) return ChromaPreviewEvent.IGNORED_INVALID_REPLY
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
        if ("set_scene" !in support) return null
        val params = JSONArray().put("color").put(rgb).put(brightness)
            .put(0).put("sudden")
        return frame(nextId++, "set_scene", params, token)
    }

    /** Caller-monotonic virtual time, no scheduler/background behavior. */
    fun advance(nowMs: Long): Boolean {
        if (nowMs < 0 || (lastActivityMs >= 0 && nowMs < lastActivityMs)) {
            close()
            return false
        }
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
    }
}
