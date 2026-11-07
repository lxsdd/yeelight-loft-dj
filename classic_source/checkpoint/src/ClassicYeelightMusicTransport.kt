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
import java.util.ArrayDeque

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
    )
    private val sessions = peers.associateWith { Session() }
    init {
        require(localIp.isNotEmpty() && validLanIp(localIp)) { "Need an active private Wi-Fi IPv4 address" }
        require(peers.isNotEmpty() && peers.size <= 4 && peers.all(::validLanIp) && peers.size == sessions.size)
        require(tcpPort in 1..65535)
        require(peers.none { it == localIp } || tcpPort != 55443)
    }

    override fun readMusic(id: String): Boolean? = withPeer(id) { peer ->
        val value = rpc(id, peer, "get_prop", "[\"music_on\"]", isRead = true)
        when {
            musicProp0.matches(value) -> false
            musicProp1.matches(value) -> true
            else -> null // unsupported/unknown is NEVER treated as music=off
        }
    }

    override fun requestStart(id: String): Boolean = withPeer(id) { peer ->
        require(peer.listener == null && peer.stream == null)
        // This listener exists before set_music(1); the device connects BACK to this phone.
        val server = ServerSocket(0, 1, InetAddress.getByName(localIp))
        server.soTimeout = 3_500
        peer.listener = server
        // If the ACK is lost, the coordinator quarantines this peer: NO speculative retry.
        rpc(id, peer, "set_music", "[1,\"$localIp\",${server.localPort}]", isRead = false) == "ok"
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
        rpc(id, peer, "set_music", "[0]", isRead = false) == "ok"
    }

    override fun closeReverse(id: String) = withPeer(id) { peer ->
        try { peer.stream?.close() } finally {
            peer.stream = null
            try { peer.listener?.close() } finally { peer.listener = null }
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

    private fun rpc(id: String, peer: Session, method: String, params: String, isRead: Boolean): String {
        val now = clockMs()
        // Extremely conservative budget independent of firmware's unknown global quota.
        // Shared for the whole Android process: stop/restart cannot reset the firmware quota.
        // Even failed/uncertain writes consume a slot, never automatically replay them.
        synchronized(commandBudget) {
            val events = commandBudget.getOrPut(id) { ArrayDeque<Long>() }
            while (events.isNotEmpty() && now - events.first >= 60_000L) events.removeFirst()
            if (events.size >= 8) throw IOException("Normal LAN command budget exhausted; no automatic retry")
            events.addLast(now)
        }
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
                val match = idPattern.find(line) ?: return@repeat
                if (match.groupValues[1].toIntOrNull() != requestId) return@repeat
                if (errorPattern.containsMatchIn(line)) throw IOException("Yeelight RPC rejected")
                if (isRead) return line
                if (okPattern.containsMatchIn(line)) return "ok"
                throw IOException("Yeelight RPC returned no explicit OK")
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
        private val commandBudget = mutableMapOf<String, ArrayDeque<Long>>()
        private val idPattern = Regex("\"id\"\\s*:\\s*(\\d+)")
        private val okPattern = Regex("\"result\"\\s*:\\s*\\[\\s*\"ok\"\\s*]")
        private val musicProp0 = Regex(".*\"result\"\\s*:\\s*\\[\\s*\"0\"\\s*].*")
        private val musicProp1 = Regex(".*\"result\"\\s*:\\s*\\[\\s*\"1\"\\s*].*")
        private val errorPattern = Regex("\"error\"\\s*:")

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
