package io.github.lxsdd.yeelightloftdj

import android.content.Context
import android.net.wifi.WifiManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Callable
import java.util.concurrent.Executors

data class YeelightDevice(
    val id: String,
    val ip: String,
    val port: Int = 55443,
    val model: String = "unknown",
    val methods: Set<String> = emptySet(),
) {
    val canFlow: Boolean get() = methods.isEmpty() || "start_cf" in methods
}

object YeelightLan {
    private const val HOST = "239.255.255.250"
    private const val PORT = 1982
    private val seq = AtomicInteger(1)
    private val ipv4 = Regex("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")
    private val search = (
        "M-SEARCH * HTTP/1.1\r\n" +
        "HOST: $HOST:$PORT\r\n" +
        "MAN: \"ssdp:discover\"\r\n" +
        "ST: wifi_bulb\r\n\r\n"
    ).toByteArray(StandardCharsets.US_ASCII)

    /** Prevent arbitrary DNS/Internet traffic via manually entered or advertised addresses. */
    fun isLocalIp(ip: String): Boolean {
        if (!ipv4.matches(ip)) return false
        return try { InetAddress.getByName(ip).isSiteLocalAddress } catch (_: Exception) { false }
    }

    fun manual(ip: String): YeelightDevice {
        require(isLocalIp(ip))
        return YeelightDevice(id = "manual-$ip", ip = ip)
    }

    fun parseAnnouncement(response: String): YeelightDevice? {
        val headers = response.lineSequence().mapNotNull { line ->
            val colon = line.indexOf(':')
            if (colon <= 0) null else line.substring(0, colon).trim().lowercase() to line.substring(colon + 1).trim()
        }.toMap()
        val location = headers["location"] ?: return null
        val match = Regex("^yeelight://($ipv4):(\\d{1,5})$", RegexOption.IGNORE_CASE).matchEntire(location) ?: return null
        val address = match.groupValues[1]
        val port = match.groupValues[2].toIntOrNull() ?: return null
        if (port !in 1..65535 || !isLocalIp(address)) return null
        return YeelightDevice(
            id = headers["id"]?.takeIf { it.isNotBlank() } ?: "ip-$address",
            ip = address,
            port = port,
            model = headers["model"] ?: "unknown",
            methods = headers["support"]?.split(Regex("\\s+"))?.toSet() ?: emptySet()
        )
    }

    /**
     * Multi-round multicast discovery. Keep listening through quiet periods:
     * previously, a single 400ms timeout prematurely ended every round.
     */
    fun discover(context: Context): List<YeelightDevice> {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("yeelight-loft-dj")
        lock.setReferenceCounted(false)
        val found = linkedMapOf<String, YeelightDevice>()
        try {
            lock.acquire()
            DatagramSocket().use { socket ->
                socket.soTimeout = 220
                val packet = DatagramPacket(search, search.size, InetAddress.getByName(HOST), PORT)
                repeat(5) {
                    socket.send(packet)
                    val deadline = System.nanoTime() + 1_150_000_000L
                    while (System.nanoTime() < deadline) {
                        try {
                            val buffer = ByteArray(4096)
                            val received = DatagramPacket(buffer, buffer.size)
                            socket.receive(received)
                            parseAnnouncement(String(received.data, received.offset, received.length, Charsets.UTF_8))
                                ?.let { found[it.ip] = it }
                        } catch (_: SocketTimeoutException) {
                            // Do not break: a bulb may reply after a quiet period.
                        }
                    }
                }
            }
        } finally {
            if (lock.isHeld) lock.release()
        }
        return found.values.sortedWith(compareBy({ it.model }, { it.ip }))
    }

    /** Safe fallback: one /24 block from the phone's Wi-Fi interface only. */
    fun subnetCandidates(ip: String): List<String> {
        if (!isLocalIp(ip)) return emptyList()
        val octets = ip.split(".")
        if (octets.size != 4) return emptyList()
        val prefix = octets.take(3).joinToString(".")
        return (1..254).map { prefix + "." + it }.filterNot { it == ip }
    }

    fun wifiSubnetCandidates(): List<String> {
        val nets = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
        while (nets.hasMoreElements()) {
            val net = nets.nextElement()
            if (!net.isUp || (!net.name.startsWith("wlan") && !net.name.startsWith("wifi"))) continue
            val addresses = net.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (address is Inet4Address && isLocalIp(address.hostAddress ?: "")) {
                    return subnetCandidates(address.hostAddress ?: "")
                }
            }
        }
        return emptyList()
    }

    /**
     * Read-only direct TCP probe for a Yeelight on port 55443.
     * A successful TCP connection alone is NOT proof of a Yeelight:
     * require a matching get_prop JSON response.
     */
    fun probe(ip: String): YeelightDevice? {
        if (!isLocalIp(ip)) return null
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, 55443), 280)
                socket.soTimeout = 500
                val payload = """{"id":9341,"method":"get_prop","params":["power"]}""" + "\r\n"
                socket.getOutputStream().write(payload.toByteArray(Charsets.UTF_8))
                socket.getOutputStream().flush()
                val line = socket.getInputStream().bufferedReader().readLine() ?: return null
                val obj = JSONObject(line)
                val values = obj.optJSONArray("result") ?: return null
                if (obj.optInt("id", -1) != 9341 || values.length() != 1) return null
                YeelightDevice(id = "ip-" + ip, ip = ip, model = "Yeelight (LAN)")
            }
        } catch (_: Exception) { null }
    }

    /**
     * Explicitly user-requested deep discovery: private /24, TCP read-only,
     * bounded 16 workers and 280ms connect timeout per address.
     */
    fun deepDiscover(): List<YeelightDevice> {
        val candidates = wifiSubnetCandidates()
        require(candidates.isNotEmpty()) { "Keine WLAN-IPv4-Adresse erkannt; bitte IP manuell eintragen." }
        val pool = Executors.newFixedThreadPool(16)
        return try {
            val tasks = candidates.map { ip -> Callable { probe(ip) } }
            pool.invokeAll(tasks).mapNotNull { it.get() }.sortedBy { it.ip }
        } finally {
            pool.shutdownNow()
        }
    }

    /** Merge scans by both device-id and IP; keep known devices when UDP misses replies. */
    fun mergeDevices(previous: List<YeelightDevice>, incoming: List<YeelightDevice>): List<YeelightDevice> {
        val merged = previous.toMutableList()
        incoming.forEach { device ->
            val known = merged.firstOrNull { it.id == device.id || it.ip == device.ip }
            merged.removeAll { it.id == device.id || it.ip == device.ip }
            val resolved = if (known != null && device.id.startsWith("ip-") && !known.id.startsWith("ip-"))
                device.copy(id = known.id, model = known.model, methods = known.methods)
            else device
            merged.add(resolved)
        }
        return merged.sortedWith(compareBy({ it.model }, { it.ip }))
    }

    /** Each JSON command ends in CRLF. We check explicit device errors. */
    fun send(device: YeelightDevice, method: String, params: List<Any>) {
        require(isLocalIp(device.ip))
        require(method in setOf("set_power", "start_cf", "stop_cf"))
        val id = seq.getAndIncrement()
        val payload = JSONObject().put("id", id).put("method", method)
            .put("params", JSONArray(params)).toString() + "\r\n"
        Socket().use { socket ->
            socket.connect(InetSocketAddress(device.ip, device.port), 1800)
            socket.soTimeout = 1800
            socket.getOutputStream().write(payload.toByteArray(Charsets.UTF_8))
            socket.getOutputStream().flush()
            val line = socket.getInputStream().bufferedReader().readLine()
                ?: error("Keine Antwort des Geräts")
            val result = JSONObject(line)
            if (result.has("error")) error(result.getJSONObject("error").optString("message", "Yeelight-Fehler"))
            if (result.optInt("id", -1) != id) error("Ungültige Geräteantwort")
        }
    }

    fun play(device: YeelightDevice, expression: String) {
        require(device.canFlow) { "start_cf wird von diesem Gerät nicht gemeldet" }
        send(device, "set_power", listOf("on", "smooth", 300))
        send(device, "start_cf", listOf(0, 1, expression))
    }

    fun stop(device: YeelightDevice) = send(device, "stop_cf", emptyList())
}
