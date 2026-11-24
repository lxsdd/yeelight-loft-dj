package loftdj.classic

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * Real opt-in Yeelight Music TCP reverse transport. Never broadcasts, never retries a command,
 * never treats a successful socket write as evidence of a physical color change.
 * Device ids are IPv4 addresses supplied explicitly by the user (not cloud or OEM account IDs).
 * Normal RPC is used only for get_prop/music start/stop. All high-rate color/brightness frames
 * go over the reverse channel. No remote command is sent before the user selects a participant.
 */
class ClassicYeelightMusicTransport(
    private val localIp: String,
    private val peers: Set<String>,
    private val tcpPort: Int = 55443,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
) : ClassicMusicCoordinator.Transport {
    private data class Session(
        var listener: ServerSocket? = null,
        var stream: Socket? = null,
        var nextId: Int = 1,
        var awaitingVerification: Boolean = false,
    )
    private val sessions = peers.associateWith { Session() }
    init {
        require(localIp.isNotEmpty() && validLanIp(localIp)) { "Need an active private Wi-Fi IPv4 address" }
        // One reverse session per explicitly selected IP. The Yeelight TCP-client
        // quota applies PER LAMP, not to the number of lamps in a group.
        // Keep every participant's own state/budget; no fixed fleet ceiling.
        require(peers.isNotEmpty() && peers.all(::validLanIp) && peers.size == sessions.size)
        require(tcpPort in 1..65535)
        require(peers.none { it == localIp } || tcpPort != 55443)
    }

    override fun readMusic(id: String): Boolean? = withPeer(id) { peer ->
        // Only the verified post-STOP read may use the reserved final slot.
        val phase = if (peer.awaitingVerification) ClassicLanRpcBudget.Phase.VERIFY_STOP
                    else ClassicLanRpcBudget.Phase.READ
        peer.awaitingVerification = false
        val value = rpc(id, peer, "get_prop", "[\"music_on\"]", isRead = true, phase = phase)
        // An incomplete or forged property reply is NOT evidence that Music is off.
        parseMusicReply(value)
    }

    override fun requestStart(id: String): Boolean = withPeer(id) { peer ->
        require(peer.listener == null && peer.stream == null)
        // This listener exists before set_music(1); the device connects BACK to this phone.
        val server = ServerSocket(0, 1, InetAddress.getByName(localIp))
        server.soTimeout = 3_500
        peer.listener = server
        // If the ACK is lost, the coordinator quarantines this peer: NO speculative retry.
        rpc(id, peer, "set_music", "[1,\"$localIp\",${server.localPort}]", isRead = false,
            phase = ClassicLanRpcBudget.Phase.START) == "ok"
    }

    override fun reverseConnected(id: String): Boolean = withPeer(id) { peer ->
        val server = peer.listener ?: return@withPeer false
        try {
            val incoming = server.accept()
            if (incoming.inetAddress.hostAddress != id) {
                incoming.close()
                return@withPeer false
            }
            incoming.tcpNoDelay = true
            incoming.soTimeout = 1500
            peer.stream = incoming
            true
        } finally {
            peer.listener = null
            server.close()
        }
    }

    override fun requestStop(id: String): Boolean = withPeer(id) { peer ->
        // Only invoked by coordinator.stop for a session it has confirmed as OWNED.
        val accepted = rpc(id, peer, "set_music", "[0]", isRead = false,
            phase = ClassicLanRpcBudget.Phase.STOP) == "ok"
        if (accepted) peer.awaitingVerification = true
        accepted
    }

    override fun closeReverse(id: String) = withPeer(id) { peer ->
        var closed = false
        try {
            try { peer.stream?.close() } finally {
                peer.stream = null
                try { peer.listener?.close() } finally { peer.listener = null }
            }
            closed = true
        } finally {
            // STOP's confirmed ACK is not proof of music_off. Keep the reserved
            // verification slot across local reverse-socket close.
            if (closed && !peer.awaitingVerification) commandBudget.retire(id, peer)
        }
        Unit
    }

    override fun sendReverseFrame(id: String, generation: Long, rgb: Int, brightness: Int): Boolean = withPeer(id) { peer ->
        require(rgb in 0..0xFFFFFF && brightness in 1..100 && generation > 0)
        val socket = peer.stream?.takeIf { it.isConnected && !it.isClosed } ?: return@withPeer false
        val body = "{\"id\":${peer.nextId++},\"method\":\"set_scene\",\"params\":[\"color\",$rgb,$brightness]}\r\n"
        // Combined hue AND brightness in one reverse-stream frame. No quota-channel fallback.
        val output = socket.getOutputStream()
        output.write(body.toByteArray(StandardCharsets.US_ASCII))
        output.flush()
        true // Transmitted bytes only. Physical state is unverified.
    }

    private fun <T> withPeer(id: String, fn: (Session) -> T): T {
        val peer = requireNotNull(sessions[id]) { "Unregistered or unselected lamp" }
        synchronized(peer) { return fn(peer) }
    }

    private fun rpc(id: String, peer: Session, method: String, params: String,
                    isRead: Boolean, phase: ClassicLanRpcBudget.Phase = ClassicLanRpcBudget.Phase.READ): String {
        val now = clockMs()
        // Extremely conservative budget independent of firmware's unknown global quota.
        // Shared for the whole Android process: stop/restart cannot reset the firmware quota.
        // Even failed/uncertain writes consume a slot, never automatically replay them.
        if (!commandBudget.admit(id, now, phase, peer))
            throw ClassicLanFailure(ClassicLanFailure.Kind.LOCAL_BUDGET)
        val requestId = peer.nextId++
        Socket().use { socket ->
            socket.connect(InetSocketAddress(id, tcpPort), 1_500)
            socket.soTimeout = 1_800
            val request = "{\"id\":$requestId,\"method\":\"$method\",\"params\":$params}\r\n"
            socket.getOutputStream().write(request.toByteArray(StandardCharsets.US_ASCII))
            socket.getOutputStream().flush()
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))
            // Bound asynchronous property events and response length. Never retry a write.
            repeat(6) {
                val line = readBoundedLine(reader) ?: throw IOException("Connection closed before matching reply")
                // Accept an ID only in the top-level response envelope,
                // never an "id" embedded in a property notification or message.
                if (correlatedReplyId(line) != requestId) return@repeat
                classifyMatchedRpcError(line)?.let { throw ClassicLanFailure(it) }
                if (isRead) {
                    if (parseMusicReply(line) != null) return line
                    throw IOException("Unverified Music state reply")
                }
                if (validatedOkReply(line)) return "ok"
                throw IOException("Unverified Yeelight command acknowledgement")
            }
            throw IOException("No matching Yeelight RPC response")
        }
    }

    private fun readBoundedLine(reader: BufferedReader): String? {
        val builder = StringBuilder()
        repeat(2048) {
            val n = reader.read()
            if (n < 0) return if (builder.isEmpty()) null else builder.toString()
            if (n == 10) return builder.toString()
            if (n != 13) builder.append(n.toChar())
        }
        throw IOException("Oversized Yeelight RPC reply")
    }

    companion object {
        private val commandBudget = ClassicLanRpcBudget()
        // Deliberately narrow protocol acceptance for the one supported
        // Yeelight Classic RPC response: complete top-level id + result.
        // Reject partial JSON, duplicate/trailing fields and nested spoofing.
        // OEM responses with unknown shapes remain uncertain, never OFF/OK.
        private val idPattern = Regex("""^\s*\{\s*"id"\s*:\s*(\d+)\s*,""")
        private val okPattern = Regex("""^\s*\{\s*"id"\s*:\s*\d+\s*,\s*"result"\s*:\s*\[\s*"ok"\s*]\s*}\s*$""")
        private val musicProp0 = Regex("""^\s*\{\s*"id"\s*:\s*\d+\s*,\s*"result"\s*:\s*\[\s*"0"\s*]\s*}\s*$""")
        private val musicProp1 = Regex("""^\s*\{\s*"id"\s*:\s*\d+\s*,\s*"result"\s*:\s*\[\s*"1"\s*]\s*}\s*$""")
        internal fun correlatedReplyId(line: String): Int? =
            idPattern.find(line)?.groupValues?.get(1)?.toIntOrNull()
        internal fun parseMusicReply(line: String): Boolean? = when {
            musicProp0.matches(line) -> false
            musicProp1.matches(line) -> true
            else -> null
        }
        internal fun validatedOkReply(line: String): Boolean = okPattern.matches(line)
        private val errorPattern = Regex("\"error\"\\s*:")
        private val quotaMessage = Regex(
            "\"error\"\\s*:\\s*\\{[^{}]{0,320}\"message\"\\s*:\\s*\"client quota exceeded\"",
            RegexOption.IGNORE_CASE)
        /** Only after this receive path has correlated the response request ID. */
        internal fun classifyMatchedRpcError(line: String): ClassicLanFailure.Kind? =
            if (!errorPattern.containsMatchIn(line)) null
            else if (quotaMessage.containsMatchIn(line)) ClassicLanFailure.Kind.DEVICE_QUOTA
            else ClassicLanFailure.Kind.DEVICE_REJECTED

        @JvmStatic fun validLanIp(address: String): Boolean {
            val octets = address.split('.')
            if (octets.size != 4 || octets.any { !it.matches(Regex("[0-9]{1,3}")) || (it.length>1 && it[0]=='0') }) return false
            val parts = octets.mapNotNull { it.toIntOrNull() }
            if (parts.size != 4 || parts.any { it !in 0..255 }) return false
            // RFC1918 only. Do not allow public, loopback, link-local, multicast or local router addresses.
            return (parts[0] == 10 || parts[0] == 172 && parts[1] in 16..31 || parts[0] == 192 && parts[1] == 168) &&
                parts[3] in 1..254
        }

        @JvmStatic fun wifiPrivateIpv4(): String? {
            val all = NetworkInterface.getNetworkInterfaces() ?: return null
            while (all.hasMoreElements()) {
                val net = all.nextElement()
                if (!net.isUp || net.isLoopback || !(net.name.startsWith("wlan") || net.name.startsWith("wifi"))) continue
                val addresses = net.inetAddresses
                while (addresses.hasMoreElements()) {
                    val ip = addresses.nextElement()
                    if (ip is Inet4Address && validLanIp(ip.hostAddress)) return ip.hostAddress
                }
            }
            return null
        }
    }
}
