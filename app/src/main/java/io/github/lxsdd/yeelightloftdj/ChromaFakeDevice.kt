package io.github.lxsdd.yeelightloftdj

import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets

/**
 * Simulated datagram only: never use this type with a network socket.
 * Its string representation is deliberately redacted because frames contain
 * a session token. All addresses in tests are synthetic RFC1918 addresses.
 */
data class ChromaFakeDatagram(
    val senderIp: String,
    val senderPort: Int,
    val payload: String,
) {
    override fun toString(): String = "ChromaFakeDatagram(redacted)"
}

/**
 * In-memory fake Yeelight Chroma v1/v2 device, independently interpreting
 * preview JSON frames. It has no Android, socket, firmware or timer calls.
 *
 * This fake is NOT a claim of hardware interoperability: responses are
 * grounded in the Yeelight Chroma Connector C++ sample, while strict
 * validation/drop behavior is our simulation policy.
 */
class ChromaFakeDevice(
    private val expectedClientIp: String,
    private val version: Int,
    private val deviceIp: String = "192.168.1.21",
    private val syntheticToken: String = "fedcba9876543210fedcba9876543210",
) {
    init {
        require(version == 1 || version == 2)
        require(Regex("^[0-9a-fA-F]{32}$").matches(syntheticToken))
    }

    private val handshakeName = if (version == 2) "udp_sess_new" else "udp_new"
    private val tokenMethod = if (version == 2) "udp_sess_token" else "udp_token"
    private val keepAliveName = if (version == 2) "udp_sess_keep_alive" else "udp_keep_alive"
    private var active = false
    private var mostRecentId = 0

    /** Failure injection controls are only for simulator tests. */
    var dropNextReply = false
    var rejectNextCommand = false

    var acceptedColorFrames = 0
        private set
    var acceptedKeepAlives = 0
        private set

    fun process(incoming: ChromaFakeDatagram): ChromaFakeDatagram? {
        if (incoming.senderIp != expectedClientIp || incoming.senderPort !in 1..65535 ||
            incoming.payload.toByteArray(StandardCharsets.UTF_8).size > 1024 ||
            incoming.payload.any { it == '\u0000' }) return null
        val packet = try { JSONObject(incoming.payload) } catch (_: Exception) { return null }
        val rawId = packet.opt("id")
        val id = if (rawId is Int) rawId else -1
        val method = packet.optString("method")
        if (id <= mostRecentId || id <= 0 || packet.opt("params") !is JSONArray) return null
        if (method != handshakeName && method != keepAliveName && method != "set_scene") return null
        // Validate token, exact parameter types and semantics BEFORE the
        // synthetic error injector. Invalid high-ID frames must not consume a
        // sequence number or a one-shot simulated firmware rejection.
        if (!validCommand(packet, method)) return null
        if (rejectNextCommand) {
            mostRecentId = id
            rejectNextCommand = false
            active = false
            return emit(id, JSONObject().put("id", id).put("error",
                JSONObject().put("code", -1).put("message", "simulated rejection")))
        }

        return when (method) {
            handshakeName -> {
                active = true
                mostRecentId = id
                emit(id, tokenReply(id))
            }
            keepAliveName -> {
                acceptedKeepAlives++
                mostRecentId = id
                emit(id, tokenReply(id))
            }
            "set_scene" -> {
                acceptedColorFrames++
                mostRecentId = id
                // The sample's color path does not wait for per-frame ACK.
                null
            }
            else -> null
        }
    }

    private fun validCommand(packet: JSONObject, method: String): Boolean {
        val p = packet.getJSONArray("params")
        return when (method) {
            handshakeName -> !active && p.length() == 0
            keepAliveName -> active && validToken(packet) && p.length() == 2 &&
                p.opt(0) == "keeplive_interval" && p.opt(1) == "10"
            "set_scene" -> {
                val rgb = p.opt(1) as? Int
                val brightness = p.opt(2) as? Int
                val duration = p.opt(3) as? Int
                active && validToken(packet) && p.length() == 5 &&
                    p.opt(0) == "color" && rgb != null && rgb in 0..0xFFFFFF &&
                    brightness != null && brightness in 1..100 &&
                    duration == 0 && p.opt(4) == "sudden"
            }
            else -> false
        }
    }

    private fun validToken(packet: JSONObject): Boolean =
        packet.optString("token") == syntheticToken

    private fun tokenReply(id: Int): JSONObject =
        JSONObject().put("id", id).put("method", tokenMethod)
            .put("params", JSONObject().put("token", syntheticToken))

    private fun emit(id: Int, json: JSONObject): ChromaFakeDatagram? {
        if (dropNextReply) {
            dropNextReply = false
            return null
        }
        return ChromaFakeDatagram(deviceIp, CHROMA_UDP_PORT, json.toString() + "\r\n")
    }

    companion object { const val CHROMA_UDP_PORT = 55444 }
}
