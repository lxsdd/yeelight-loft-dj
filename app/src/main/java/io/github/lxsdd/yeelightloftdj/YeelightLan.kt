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

enum class FlowStartState { VERIFIED, UNVERIFIED }

data class YeelightDevice(
    val id: String,
    val ip: String,
    val port: Int = 55443,
    val model: String = "unknown",
    val methods: Set<String> = emptySet(),
    val lanName: String = "",
) {
    val canFlow: Boolean get() = if (methods.isEmpty()) {
        // A TCP-only probe has no SUPPORT evidence. Preserve cautious
        // diagnostic attempts for unknown devices, never for documented mono.
        model.lowercase() != "mono"
    } else {
        // Our chase frames are RGB (mode 1). start_cf alone could be a
        // monochrome/temperature flow and is not proof of RGB capability.
        "start_cf" in methods && "set_rgb" in methods
    }
}

object YeelightLan {
    private const val HOST = "239.255.255.250"
    private const val PORT = 1982
    private val seq = AtomicInteger(1)
    @Volatile var lastSearchPackets: Int = 0
        private set
    @Volatile var lastSearchInvalidPackets: Int = 0
        private set
    @Volatile var lastSearchRoundUnique: List<Int> = emptyList()
        private set
    @Volatile var lastSearchSourceAddress: String = "unbekannt"
        private set
    @Volatile var lastSearchSourceMismatches: Int = 0
        private set
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
            methods = headers["support"]?.split(Regex("\\s+"))?.filter { it.isNotBlank() }?.toSet() ?: emptySet(),
            lanName = headers["name"]?.trim().orEmpty()
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
        var packets = 0
        var invalid = 0
        var mismatches = 0
        val roundUnique = mutableListOf<Int>()
        val source = wifiIpv4Address()
            ?: error("Keine lokale WLAN-IPv4-Adresse erkannt; Multicast nicht auf Mobilfunk umleiten")
        try {
            lock.acquire()
            // Bind explicitly to the Wi-Fi IP so Android/VPN routing cannot
            // accidentally send M-SEARCH via a different active interface.
            DatagramSocket(InetSocketAddress(InetAddress.getByName(source), 0)).use { socket ->
                lastSearchSourceAddress = socket.localAddress.hostAddress ?: "unbekannt"
                socket.soTimeout = 220
                val packet = DatagramPacket(search, search.size, InetAddress.getByName(HOST), PORT)
                repeat(5) {
                    val round = mutableSetOf<String>()
                    socket.send(packet)
                    val deadline = System.nanoTime() + 1_150_000_000L
                    while (System.nanoTime() < deadline) {
                        try {
                            val buffer = ByteArray(4096)
                            val received = DatagramPacket(buffer, buffer.size)
                            socket.receive(received)
                            packets++
                            val parsed = parseAnnouncement(String(received.data, received.offset, received.length, Charsets.UTF_8))
                            if (parsed == null) invalid++ else {
                                round.add(parsed.ip)
                                if (received.address.hostAddress != parsed.ip) mismatches++
                                found[parsed.ip] = parsed
                            }
                        } catch (_: SocketTimeoutException) {
                            // Do not break: a bulb may reply after a quiet period.
                        }
                    }
                    roundUnique.add(round.size)
                }
            }
        } finally {
            lastSearchPackets = packets
            lastSearchInvalidPackets = invalid
            lastSearchRoundUnique = roundUnique.toList()
            lastSearchSourceMismatches = mismatches
            if (lock.isHeld) lock.release()
        }
        return found.values.sortedWith(compareBy({ it.model }, { it.ip }))
    }


    /**
     * Explicit, read-only experiment: send the Yeelight search payload as
     * directed UDP to already confirmed local TCP bulbs. Some firmwares ignore
     * unicast search by design: a positive reply is useful evidence, a missing
     * reply is NOT evidence of a fault.
     */
    fun directedUdpProbe(targets: List<YeelightDevice>): Set<String> {
        val source = wifiIpv4Address() ?: error("Kein WLAN aktiv")
        val allowed = targets.asSequence()
            .filter { isLocalIp(it.ip) }
            .distinctBy { it.ip }
            .take(24).toList()
        val answered = mutableSetOf<String>()
        DatagramSocket(InetSocketAddress(InetAddress.getByName(source), 0)).use { socket ->
            socket.soTimeout = 100
            allowed.forEach { device ->
                val address = InetAddress.getByName(device.ip)
                socket.send(DatagramPacket(search, search.size, address, PORT))
                val deadline = System.nanoTime() + 360_000_000L
                while (System.nanoTime() < deadline) {
                    try {
                        val buffer = ByteArray(4096)
                        val reply = DatagramPacket(buffer, buffer.size)
                        socket.receive(reply)
                        val parsed = parseAnnouncement(
                            String(reply.data, reply.offset, reply.length, Charsets.UTF_8)
                        )
                        if (parsed?.ip == device.ip && reply.address.hostAddress == device.ip) {
                            answered.add(device.ip)
                        }
                    } catch (_: SocketTimeoutException) {
                        // Continue through the bounded deadline.
                    }
                }
            }
        }
        return answered
    }

    /** Safe fallback: one /24 block from the phone's Wi-Fi interface only. */
    fun subnetCandidates(ip: String): List<String> {
        if (!isLocalIp(ip)) return emptyList()
        val octets = ip.split(".")
        if (octets.size != 4) return emptyList()
        val prefix = octets.take(3).joinToString(".")
        return (1..254).map { prefix + "." + it }.filterNot { it == ip }
    }

    /** Prefer the actual Wi-Fi interface to Android's generic default network. */
    fun wifiIpv4Address(): String? {
        val nets = NetworkInterface.getNetworkInterfaces() ?: return null
        while (nets.hasMoreElements()) {
            val net = nets.nextElement()
            if (!net.isUp || (!net.name.startsWith("wlan") && !net.name.startsWith("wifi"))) continue
            val addresses = net.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (address is Inet4Address && isLocalIp(address.hostAddress ?: "")) {
                    return address.hostAddress
                }
            }
        }
        return null
    }

    fun wifiSubnetCandidates(): List<String> =
        wifiIpv4Address()?.let(::subnetCandidates) ?: emptyList()

    /**
     * Read-only direct TCP probe for a Yeelight on port 55443.
     * A successful TCP connection alone is NOT proof of a Yeelight:
     * require a matching get_prop JSON response.
     */
    fun probe(ip: String): YeelightDevice? {
        if (!isLocalIp(ip)) return null
        // Older Yeelight firmwares may reject "name". In that case retry
        // with the established power-only probe, rather than lose the bulb.
        for (properties in listOf(listOf("power", "name"), listOf("power"))) {
            val result = try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(ip, 55443), 280)
                    socket.soTimeout = 500
                    val payload = JSONObject()
                        .put("id", 9341)
                        .put("method", "get_prop")
                        .put("params", JSONArray(properties))
                        .toString() + "\r\n"
                    socket.getOutputStream().write(payload.toByteArray(Charsets.UTF_8))
                    socket.getOutputStream().flush()
                    val line = socket.getInputStream().bufferedReader().readLine()
                        ?: return@use null
                    val obj = JSONObject(line)
                    val values = obj.optJSONArray("result") ?: return@use null
                    if (obj.optInt("id", -1) != 9341 || values.length() < 1) return@use null
                    val lanName = if (properties.size == 2) values.optString(1).trim() else ""
                    YeelightDevice(id = "ip-" + ip, ip = ip,
                        model = "unknown", lanName = lanName)
                }
            } catch (_: Exception) { null }
            if (result != null) return result
        }
        return null
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

    /** A direct TCP probe cannot report the bulb's model; preserve earlier SSDP metadata. */
    fun mergeDevices(previous: List<YeelightDevice>, incoming: List<YeelightDevice>): List<YeelightDevice> {
        val merged = previous.toMutableList()
        incoming.forEach { candidate ->
            val known = merged.firstOrNull { it.id == candidate.id || it.ip == candidate.ip }
            merged.removeAll { it.id == candidate.id || it.ip == candidate.ip }
            val oldModel = known?.model.orEmpty()
            val newModel = candidate.model
            val chosenModel = when {
                isSpecificModel(newModel) -> newModel
                isSpecificModel(oldModel) -> oldModel
                else -> "unknown"
            }
            val chosenId = when {
                candidate.id.startsWith("ip-") && known != null && !known.id.startsWith("ip-") -> known.id
                candidate.id.startsWith("manual-") && known != null -> known.id
                else -> candidate.id
            }
            val chosenName = candidate.lanName.ifBlank { known?.lanName.orEmpty() }
            val chosenMethods = candidate.methods.ifEmpty { known?.methods ?: emptySet() }
            merged.add(candidate.copy(
                id = chosenId, model = chosenModel, lanName = chosenName, methods = chosenMethods,
            ))
        }
        return merged.sortedWith(compareBy({ it.model }, { it.ip }))
    }

    fun isSpecificModel(model: String): Boolean =
        model.isNotBlank() && model != "unknown" && model != "Yeelight" && model != "Yeelight (LAN)"

    internal fun matchesReplyId(response: JSONObject, id: Int): Boolean =
        response.has("id") && response.optInt("id", -1) == id

    private class RejectedCommand(message: String) : IllegalStateException(message)

    /** Each command is CRLF-delimited and must return the matching explicit OK. */
    fun send(device: YeelightDevice, method: String, params: List<Any>) {
        require(isLocalIp(device.ip))
        require(method in setOf("set_power", "start_cf", "stop_cf", "set_scene",
            "set_bright", "set_rgb", "set_ct_abx", "set_hsv"))
        val id = seq.getAndIncrement()
        val payload = JSONObject().put("id", id).put("method", method)
            .put("params", JSONArray(params)).toString() + "\r\n"
        Socket().use { socket ->
            socket.connect(InetSocketAddress(device.ip, device.port), 1800)
            socket.soTimeout = 1800
            socket.getOutputStream().write(payload.toByteArray(Charsets.UTF_8))
            socket.getOutputStream().flush()
            val reader = socket.getInputStream().bufferedReader()
            var answer: JSONObject? = null
            repeat(12) {
                if (answer == null) {
                    val line = reader.readLine() ?: error("Keine Antwort auf $method")
                    val candidate = JSONObject(line)
                    if (matchesReplyId(candidate, id)) answer = candidate
                }
            }
            val reply = answer ?: error("Keine passende Geräteantwort auf $method")
            if (reply.has("error")) {
                throw RejectedCommand("$method abgelehnt: " +
                    reply.getJSONObject("error").optString("message", "Yeelight-Fehler"))
            }
            check(reply.optJSONArray("result")?.optString(0) == "ok") {
                "Keine OK-Bestätigung für $method"
            }
        }
    }

    internal fun flowStateFromValue(raw: String?): Boolean? = when (raw) {
        "1" -> true
        "0" -> false
        else -> null
    }

    /**
     * Report the actual firmware 'flowing' property. Never treat a command OK
     * as evidence that the animation is still active.
     */
    fun readFlowing(device: YeelightDevice): Boolean? {
        require(isLocalIp(device.ip))
        val requestId = seq.getAndIncrement()
        val payload = JSONObject().put("id", requestId).put("method", "get_prop")
            .put("params", JSONArray(listOf("flowing"))).toString() + "\r\n"
        Socket().use { socket ->
            socket.connect(InetSocketAddress(device.ip, device.port), 1800)
            socket.soTimeout = 1800
            socket.getOutputStream().write(payload.toByteArray(Charsets.UTF_8))
            socket.getOutputStream().flush()
            val reader = socket.getInputStream().bufferedReader()
            var answer: JSONObject? = null
            repeat(12) {
                if (answer == null) {
                    val candidate = JSONObject(reader.readLine()
                        ?: error("Keine Antwort auf flowing-Abfrage"))
                    if (matchesReplyId(candidate, requestId)) answer = candidate
                }
            }
            val response = answer ?: error("Keine passende flowing-Antwort")
            if (response.has("error")) {
                error("flowing-Abfrage: " +
                    response.getJSONObject("error").optString("message", "Gerätefehler"))
            }
            return flowStateFromValue(response.optJSONArray("result")?.optString(0))
        }
    }

    internal fun preferAtomicFlowStart(methods: Set<String>): Boolean =
        methods.isEmpty() || "set_scene" in methods

    /** One status read over a fresh TCP connection; no fabricated device states. */
    fun readStatus(device: YeelightDevice): LampSnapshot {
        require(isLocalIp(device.ip))
        val requestId = seq.getAndIncrement()
        val params = JSONArray(LampSnapshot.properties)
        val payload = JSONObject().put("id", requestId).put("method", "get_prop")
            .put("params", params).toString() + "\r\n"
        Socket().use { socket ->
            socket.connect(InetSocketAddress(device.ip, device.port), 1800)
            socket.soTimeout = 1800
            socket.getOutputStream().write(payload.toByteArray(Charsets.UTF_8))
            socket.getOutputStream().flush()
            val reader = socket.getInputStream().bufferedReader()
            var answer: JSONObject? = null
            repeat(12) {
                if (answer == null) {
                    val candidate = JSONObject(reader.readLine() ?: error("Keine Statusantwort"))
                    if (matchesReplyId(candidate, requestId)) answer = candidate
                }
            }
            val response = answer ?: error("Keine passende Statusantwort")
            if (response.has("error")) error(response.getJSONObject("error").optString("message", "Statusfehler"))
            val values = response.optJSONArray("result") ?: error("Statusdaten fehlen")
            return LampSnapshot.fromValues((0 until values.length()).map { values.optString(it) })
                ?: error("Unvollständiger Gerätestatus; kein Schaltzustand angenommen")
        }
    }

    /** Only explicitly requested commands can reach the local lamp. */
    fun manualControl(device: YeelightDevice, command: LampCommand) {
        require(command.method in setOf("set_power", "set_bright", "set_rgb", "set_ct_abx", "set_hsv"))
        if (device.methods.isNotEmpty() && command.method !in device.methods) {
            error(device.model + " meldet keine Unterstützung für " + command.method)
        }
        send(device, command.method, command.params)
    }

    /**
     * Identify RGB and white-only desk lamps with a visible 2.5 second pulse.
     * Switch to a colour only when we can restore the actual previous mode.
     * All restoration is attempted even if flashing partially fails.
     */
    fun identify(device: YeelightDevice): LampSnapshot {
        val before = readStatus(device)
        val plan = LampIdentification.prepare(before, device.methods)
        var touched = false
        var failure: Exception? = null
        try {
            touched = true
            plan.pulse.forEach { manualControl(device, it) }
            Thread.sleep(2500)
        } catch (e: Exception) {
            failure = e
        } finally {
            if (touched) plan.restore.forEach { command ->
                try { manualControl(device, command) }
                catch (e: Exception) {
                    if (failure == null) failure = e
                    else failure?.addSuppressed(e)
                }
            }
        }
        if (failure != null) throw failure!!
        return before
    }

    /**
     * Prefer the protocol's atomic set_scene("cf") command: it can start a
     * color flow even when the bulb was off. This avoids the power-on race.
     * For known older devices without set_scene, use a settled power-on.
     */
    fun play(device: YeelightDevice, expression: String): FlowStartState {
        require(device.canFlow) { "start_cf wird von diesem Gerät nicht gemeldet" }
        fun legacyStart() {
            send(device, "set_power", listOf("on", "smooth", 300))
            Thread.sleep(350) // start_cf is only accepted after the bulb is ON
            send(device, "start_cf", listOf(0, 1, expression))
        }
        if (preferAtomicFlowStart(device.methods)) {
            try {
                send(device, "set_scene", listOf("cf", 0, 1, expression))
            } catch (rejection: RejectedCommand) {
                // TCP-only discovery has no capability list. Only a definite
                // command rejection permits a fallback; never retry timeouts
                // as the bulb might already be running the requested scene.
                if (device.methods.isNotEmpty()) throw rejection
                legacyStart()
            }
        } else {
            legacyStart()
        }
        Thread.sleep(150)
        // Some firmware versions accept a valid start_cf/set_scene but do not
        // implement 'flowing' in get_prop. A missing property is NOT proof of
        // failure. Never fabricate a 'running' confirmation either.
        val flowing = runCatching { readFlowing(device) }.getOrNull()
        check(flowing != false) {
            "Gerät bestätigt flowing=0: Animation läuft nicht"
        }
        return classifyFlowStart(flowing)
    }

    internal fun classifyFlowStart(flowing: Boolean?): FlowStartState =
        if (flowing == true) FlowStartState.VERIFIED
        else FlowStartState.UNVERIFIED

    fun stop(device: YeelightDevice) {
        try {
            send(device, "stop_cf", emptyList())
        } catch (failure: Exception) {
            // A no-longer-running flow is already safely stopped. Only clear
            // the persisted stop marker when the bulb confirms flowing=0.
            val flowing = runCatching { readFlowing(device) }.getOrNull()
            if (flowing != false) {
                throw IllegalStateException(
                    "stop_cf fehlgeschlagen (flowing=" +
                        (flowing?.toString() ?: "unbekannt") + "): " +
                        (failure.message ?: failure.javaClass.simpleName), failure
                )
            }
        }
    }
}
