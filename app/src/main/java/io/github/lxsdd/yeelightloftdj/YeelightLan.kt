package io.github.lxsdd.yeelightloftdj

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
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
import java.util.concurrent.ConcurrentHashMap
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
    private const val QUARANTINED_IPS_KEY = "firmware-quota-blocked-ips"
    @Volatile private var quotaStore: SharedPreferences? = null
    private val commandGate = DeviceCommandGate(
        onQuarantined = { ip -> writeQuarantine(ip, blocked = true) },
        onRecovered = { ip -> writeQuarantine(ip, blocked = false) }
    )
    private val tcp = YeelightTcpSessions()

    /**
     * The firmware-quota lockout must survive an Android activity/process
     * restart. The stored local IPs remain in app-private preferences.
     */
    @Synchronized
    fun installQuotaState(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(
            "lan-firmware-safety", Context.MODE_PRIVATE
        )
        quotaStore = prefs
        prefs.getStringSet(QUARANTINED_IPS_KEY, emptySet())?.toList()?.forEach {
            commandGate.markQuarantined(it)
        }
    }

    @Synchronized
    private fun writeQuarantine(ip: String, blocked: Boolean) {
        val store = quotaStore ?: return
        val ips = store.getStringSet(QUARANTINED_IPS_KEY, emptySet()).orEmpty().toMutableSet()
        if (blocked) ips.add(ip) else ips.remove(ip)
        // Commit before a later process death; never publish these IPs.
        check(store.edit().putStringSet(QUARANTINED_IPS_KEY, ips).commit()) {
            "LAN-Quarantäne konnte nicht dauerhaft gespeichert werden"
        }
    }

    fun isLanQuarantined(device: YeelightDevice): Boolean =
        commandGate.isQuarantined(device.ip)

    /**
     * A single explicitly requested read-only get_prop. Never auto-run it in
     * discovery, scenes, UI recomposition or background status refresh.
     */
    fun verifyLanRecovery(device: YeelightDevice): String {
        require(isLocalIp(device.ip))
        return commandGate.explicitRecoveryProbe(device.ip) {
            val result = tcp.request(device.ip, device.port, seq.getAndIncrement(),
                "get_prop", listOf("power"))
            if (result.has("error")) {
                error("LAN-Recovery durch Firmware abgelehnt: " +
                    result.optJSONObject("error")?.optString("message", "Gerätefehler"))
            }
            result.optJSONArray("result")?.optString(0)
                ?.takeIf { it == "on" || it == "off" }
                ?: error("LAN-Recovery: keine gültige Statusantwort; Sperre bleibt bestehen")
        }
    }
    /** Release LAN slots when the user leaves the app, including for Yeelight itself. */
    fun releaseTcpConnections() = tcp.closeAll()
    private const val HOST = "239.255.255.250"
    private const val PORT = 1982
    private val seq = AtomicInteger(1)
    @Volatile var lastSearchPackets: Int = 0
        private set
    @Volatile var lastSearchInvalidPackets: Int = 0
        private set
    @Volatile var lastSearchRoundUnique: List<Int> = emptyList()
        private set
    @Volatile var lastSearchRoundResponders: List<Set<String>> = emptyList()
        private set
    @Volatile var lastSearchRepliesPerIp: Map<String, Int> = emptyMap()
        private set
    @Volatile var lastSearchBoundToWifiNetwork: Boolean = false
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
    private fun wifiNetworkFor(context: Context, sourceIp: String): Network? {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as ConnectivityManager
        return manager.allNetworks.firstOrNull { network ->
            val cap = manager.getNetworkCapabilities(network)
            val link = manager.getLinkProperties(network)
            cap?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
                link?.linkAddresses?.any {
                    it.address.hostAddress == sourceIp
                } == true
        }
    }

    fun discover(context: Context, rounds: Int = 5, listenMs: Long = 1150L): List<YeelightDevice> {
        require(rounds in 1..12 && listenMs in 500L..2000L) {
            "Ungültige Grenzen für die lokale UDP-Diagnose"
        }
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("yeelight-loft-dj")
        lock.setReferenceCounted(false)
        val found = linkedMapOf<String, YeelightDevice>()
        var packets = 0
        var invalid = 0
        var mismatches = 0
        val roundUnique = mutableListOf<Int>()
        val roundResponders = mutableListOf<Set<String>>()
        val receivedByIp = mutableMapOf<String, Int>()
        val source = wifiIpv4Address()
            ?: error("Keine lokale WLAN-IPv4-Adresse erkannt; Multicast nicht auf Mobilfunk umleiten")
        val wifiNetwork = wifiNetworkFor(context, source)
        lastSearchBoundToWifiNetwork = false
        try {
            lock.acquire()
            // Bind explicitly to the Wi-Fi IP so Android/VPN routing cannot
            // accidentally send M-SEARCH via a different active interface.
            DatagramSocket(InetSocketAddress(InetAddress.getByName(source), 0)).use { socket ->
                // Only this socket is bound to Android's Wi-Fi Network.
                // Never change the whole process routing or touch mobile data.
                wifiNetwork?.bindSocket(socket)
                lastSearchBoundToWifiNetwork = wifiNetwork != null
                lastSearchSourceAddress = socket.localAddress.hostAddress ?: "unbekannt"
                socket.soTimeout = 220
                val packet = DatagramPacket(search, search.size, InetAddress.getByName(HOST), PORT)
                repeat(rounds) {
                    val round = mutableSetOf<String>()
                    socket.send(packet)
                    val deadline = System.nanoTime() + listenMs * 1_000_000L
                    while (System.nanoTime() < deadline) {
                        try {
                            val buffer = ByteArray(4096)
                            val received = DatagramPacket(buffer, buffer.size)
                            socket.receive(received)
                            packets++
                            val parsed = parseAnnouncement(String(received.data, received.offset, received.length, Charsets.UTF_8))
                            if (parsed == null) invalid++ else {
                                round.add(parsed.ip)
                                receivedByIp[parsed.ip] = (receivedByIp[parsed.ip] ?: 0) + 1
                                if (received.address.hostAddress != parsed.ip) mismatches++
                                found[parsed.ip] = parsed
                            }
                        } catch (_: SocketTimeoutException) {
                            // Do not break: a bulb may reply after a quiet period.
                        }
                    }
                    roundUnique.add(round.size)
                    roundResponders.add(round.toSet())
                }
            }
        } finally {
            lastSearchPackets = packets
            lastSearchInvalidPackets = invalid
            lastSearchRoundUnique = roundUnique.toList()
            lastSearchRoundResponders = roundResponders.toList()
            lastSearchRepliesPerIp = receivedByIp.toMap()
            lastSearchSourceMismatches = mismatches
            if (lock.isHeld) lock.release()
        }
        return found.values.sortedWith(compareBy({ it.model }, { it.ip }))
    }


    /**
     * Complement M-SEARCH multicast with a bounded, directed M-SEARCH to
     * already known, TCP-confirmed bulbs. Responses include real Yeelight
     * model/id/support; unlike TCP get_prop, they prove capabilities.
     * A directed UDP responder is NEVER counted as multicast discovery.
     */
    fun directedUdpDiscover(
        context: Context,
        targets: List<YeelightDevice>,
    ): List<YeelightDevice> {
        val source = wifiIpv4Address() ?: error("Kein WLAN aktiv")
        val allowed = targets.asSequence()
            .filter { isLocalIp(it.ip) }
            .distinctBy { it.ip }.take(24).toList()
        if (allowed.isEmpty()) return emptyList()
        val expected = allowed.map { it.ip }.toSet()
        val responses = linkedMapOf<String, YeelightDevice>()
        val endpoint = InetAddress.getByName(source)
        DatagramSocket(InetSocketAddress(endpoint, 0)).use { socket ->
            wifiNetworkFor(context, source)?.bindSocket(socket)
            socket.soTimeout = 100
            // At most two query datagrams per device. Collect all responses
            // on the same socket to avoid per-bulb receiver races and churn.
            repeat(2) {
                val missing = allowed.filter { it.ip !in responses }
                if (missing.isEmpty()) return@use
                missing.forEach { device ->
                    socket.send(DatagramPacket(search, search.size,
                        InetAddress.getByName(device.ip), PORT))
                }
                val deadline = System.nanoTime() + 700_000_000L
                while (System.nanoTime() < deadline) {
                    try {
                        val buffer = ByteArray(4096)
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        val parsed = parseAnnouncement(String(packet.data,
                            packet.offset, packet.length, Charsets.UTF_8))
                        // A malicious advertisement from a different sender
                        // cannot upgrade a saved bulb or its supported methods.
                        if (parsed != null && parsed.ip in expected &&
                            packet.address.hostAddress == parsed.ip) {
                            responses[parsed.ip] = parsed
                        }
                    } catch (_: SocketTimeoutException) {
                        // Respect the bounded 700ms collection window.
                    }
                }
            }
        }
        return responses.values.toList()
    }

    /** Positive direct-UDP discovery is useful even without multicast. */
    fun directedUdpProbe(context: Context, targets: List<YeelightDevice>): Set<String> =
        directedUdpDiscover(context, targets).map { it.ip }.toSet()

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
        return probeEndpoint(ip, 55443)
    }

    /**
     * Probe one endpoint without flooding it. Each actual get_prop is charged
     * against the SAME per-IP budget as manual control, scenes and recovery.
     * A missing TCP reply is NOT a reason to open a second connection: the
     * device may have processed the request even if its ACK was lost.
     *
     * Only an explicit, non-quota firmware rejection of the optional "name"
     * field permits the legacy power-only fallback. Keep this distinct from
     * device discovery: successful TCP alone never proves a Yeelight bulb.
     */
    internal fun probeEndpoint(
        ip: String,
        port: Int,
        gate: DeviceCommandGate = commandGate,
        connectTimeoutMs: Int = 280,
        readTimeoutMs: Int = 500,
    ): YeelightDevice? {
        require(port in 1..65535 && connectTimeoutMs > 0 && readTimeoutMs > 0)
        var tryLegacy = false
        for (properties in listOf(listOf("power", "name"), listOf("power"))) {
            if (properties.size == 1 && !tryLegacy) break
            try {
                return gate.run(ip) {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(ip, port), connectTimeoutMs)
                        socket.soTimeout = readTimeoutMs
                        val requestId = seq.getAndIncrement()
                        val payload = JSONObject()
                            .put("id", requestId)
                            .put("method", "get_prop")
                            .put("params", JSONArray(properties))
                            .toString() + "\r\n"
                        socket.getOutputStream().write(payload.toByteArray(Charsets.UTF_8))
                        socket.getOutputStream().flush()
                        // Devices may emit unsolicited props; never mistake one
                        // for the response, and never accept a mismatching ID.
                        var response: JSONObject? = null
                        val reader = socket.getInputStream().bufferedReader()
                        for (ignored in 0 until 8) {
                            val line = reader.readLine()
                                ?: break
                            val candidate = JSONObject(line)
                            if (matchesReplyId(candidate, requestId)) {
                                response = candidate
                                break
                            }
                        }
                        if (response == null) return@run null
                        if (response.has("error")) {
                            throw RejectedCommand(
                                "get_prop abgelehnt: " + response.getJSONObject("error")
                                    .optString("message", "Yeelight-Fehler")
                            )
                        }
                        val values = response.optJSONArray("result") ?: return@run null
                        // A valid reply with no real power property is not
                        // sufficient evidence of a Yeelight device.
                        if (values.optString(0) !in listOf("on", "off")) return@run null
                        YeelightDevice(
                            id = "ip-$ip", ip = ip, model = "unknown",
                            lanName = if (properties.size == 2) values.optString(1).trim() else ""
                        )
                    }
                }
            } catch (e: RejectedCommand) {
                // Firmware quota errors are terminal for this scan. The gate
                // already applied the per-lamp cooldown to ALL command paths.
                if (QuotaRecoveryPolicy.isQuotaRejection(e.message)) return null
                if (properties.size != 2 || !isUnsupportedProbeProperty(e.message)) return null
                tryLegacy = true
            } catch (_: Exception) {
                // Timeout, closed socket, malformed JSON or exhausted budget:
                // never send a speculative second get_prop on another socket.
                return null
            }
        }
        return null
    }

    /** Only an explicit unsupported-property response justifies a second probe. */
    internal fun isUnsupportedProbeProperty(message: String?): Boolean {
        val text = message?.lowercase() ?: return false
        return text.contains("unsupported") || text.contains("not support") ||
            text.contains("invalid param") || text.contains("unknown property")
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

    /**
     * Power and verification use one TCP session, but are TWO distinct LAN
     * requests. Never charge two budget tokens at one timestamp and then send
     * the two physical packets back-to-back: each request must pass the shared
     * paced gate separately. No replay of an ambiguous power command.
     */
    fun setPowerVerified(device: YeelightDevice, on: Boolean): PowerProbe {
        return try {
            directSetPowerVerified(device, on).also { result ->
                if (result.outcome == PowerOutcome.REJECTED)
                    commandGate.recordFirmwareRejection(device.ip, result.message)
            }
        } catch (e: DeviceCommandGate.Deferred) {
            PowerProbe(PowerOutcome.REJECTED,
                message = e.message ?: "Lokales Yeelight-Sicherheitsbudget erreicht")
        }
    }

    private fun directSetPowerVerified(device: YeelightDevice, on: Boolean): PowerProbe {
        require(isLocalIp(device.ip))
        if (device.methods.isNotEmpty() && "set_power" !in device.methods) {
            return PowerProbe(PowerOutcome.UNSUPPORTED,
                message = "Gerät meldet set_power nicht als unterstützt")
        }
        val started = System.nanoTime()
        fun ms(): Long = (System.nanoTime()-started)/1_000_000L
        var ackMs: Long? = null
        try {
            val powerId=seq.getAndIncrement()
            val result=commandGate.run(device.ip) {
                tcp.request(device.ip,device.port,powerId,"set_power",
                    listOf(if(on) "on" else "off","sudden",0))
            }
            if (result.has("error")) {
                return PowerProbe(PowerOutcome.REJECTED,totalMs=ms(),
                    message=result.optJSONObject("error")?.optString("message","Gerätefehler")
                        ?: "Befehl abgelehnt")
            }
            if (result.optJSONArray("result")?.optString(0)!="ok") {
                return PowerProbe(PowerOutcome.NO_ACK,totalMs=ms(),
                    message="Keine gültige OK-Bestätigung")
            }
            ackMs=ms()
            if (device.methods.isNotEmpty() && "get_prop" !in device.methods) {
                return PowerProbe(PowerOutcome.ACK_ONLY,ackMs=ackMs,totalMs=ms(),
                    message="get_prop nicht gemeldet")
            }
            Thread.sleep(75)
            val observed=runCatching {
                val response=commandGate.run(device.ip) {
                    val reply=tcp.request(device.ip,device.port,seq.getAndIncrement(),
                        "get_prop", listOf("power"))
                    check(!reply.has("error")) {
                        "Statusabfrage abgelehnt: " +
                            reply.optJSONObject("error")?.optString("message","Gerätefehler")
                    }
                    reply
                }
                when(response.optJSONArray("result")?.optString(0)) {
                    "on" -> true
                    "off" -> false
                    else -> null
                }
            }
            return PowerProbe(
                PowerVerificationPolicy.outcome(true,on,observed.getOrNull()),
                ackMs=ackMs,
                statusMs=if(observed.isSuccess)ms() else null,
                totalMs=ms(),
                message=observed.exceptionOrNull()?.message.orEmpty())
        } catch (e:Exception) {
            return PowerProbe(if(ackMs!=null)PowerOutcome.ACK_ONLY
                else PowerOutcome.NO_ACK,
                ackMs=ackMs,totalMs=ms(),
                message=e.message?:e.javaClass.simpleName)
        }
    }

    /** Each command reuses one per-lamp TCP channel with matching explicit OK. */
    fun send(device: YeelightDevice, method: String, params: List<Any>) {
        commandGate.run(device.ip) { directSend(device, method, params) }
    }

    private fun directSend(device: YeelightDevice, method: String, params: List<Any>) {
        require(isLocalIp(device.ip))
        require(method in setOf("set_power","start_cf","stop_cf","set_scene",
            "set_bright","set_rgb","set_ct_abx","set_hsv","set_music"))
        val result=tcp.request(device.ip,device.port,seq.getAndIncrement(),method,params)
        if(result.has("error"))throw RejectedCommand("$method abgelehnt: " +
            result.getJSONObject("error").optString("message","Yeelight-Fehler"))
        check(result.optJSONArray("result")?.optString(0)=="ok") {
            "Keine OK-Bestätigung für $method"
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
    fun readFlowing(device: YeelightDevice): Boolean? =
        commandGate.run(device.ip) { directReadFlowing(device) }

    private fun directReadFlowing(device: YeelightDevice): Boolean? {
        require(isLocalIp(device.ip))
        val response=tcp.request(device.ip,device.port,seq.getAndIncrement(),
            "get_prop",listOf("flowing"))
        if(response.has("error"))error("flowing-Abfrage abgelehnt: " +
            response.getJSONObject("error").optString("message","Gerätefehler"))
        return flowStateFromValue(response.optJSONArray("result")?.optString(0))
    }

    /**
     * A SINGLE Yeelight get_prop command reads flowing and all restorable
     * power/colour properties on ONE TCP connection. No write, no retries.
     * An empty/unsupported flowing response is UNKNOWN, never "off".
     */
    data class RecoveryObservation(
        val flowing: Boolean?,
        val snapshot: LampSnapshot?,
        val powerOn: Boolean?,
    )

    internal fun parseRecoveryProperties(values: List<String>): RecoveryObservation {
        val powerOn = when (values.getOrNull(1)) {
            "on" -> true
            "off" -> false
            else -> null
        }
        // A lamp may legitimately report bright=0 or incomplete color fields
        // when powered OFF. Power evidence must survive snapshot parse failure.
        return RecoveryObservation(
            flowing = flowStateFromValue(values.getOrNull(0)),
            snapshot = LampSnapshot.fromValues(values.drop(1)),
            powerOn = powerOn
        )
    }

    fun readRecoveryObservation(device: YeelightDevice): RecoveryObservation =
        commandGate.run(device.ip) { directReadRecoveryObservation(device) }

    private fun directReadRecoveryObservation(device: YeelightDevice): RecoveryObservation {
        require(isLocalIp(device.ip))
        val reply=tcp.request(device.ip,device.port,seq.getAndIncrement(),"get_prop",
            listOf("flowing")+LampSnapshot.properties)
        if(reply.has("error"))error("Zustandsprüfung abgelehnt: " +
            reply.getJSONObject("error").optString("message","Yeelight-Fehler"))
        val values=reply.optJSONArray("result")?:error("Statuswerte fehlen")
        return parseRecoveryProperties((0 until values.length()).map { values.optString(it) })
    }

    internal fun preferAtomicFlowStart(methods: Set<String>): Boolean =
        methods.isEmpty() || "set_scene" in methods

    /** One status read over the shared TCP channel; no fabricated states. */
    fun readStatus(device: YeelightDevice): LampSnapshot =
        commandGate.run(device.ip) { directReadStatus(device) }

    private fun directReadStatus(device: YeelightDevice): LampSnapshot {
        require(isLocalIp(device.ip))
        val reply=tcp.request(device.ip,device.port,seq.getAndIncrement(),"get_prop",
            LampSnapshot.properties)
        if(reply.has("error"))error("Statusabfrage abgelehnt: " +
            reply.getJSONObject("error").optString("message","Gerätefehler"))
        val values=reply.optJSONArray("result")?:error("Statusdaten fehlen")
        return LampSnapshot.fromValues((0 until values.length()).map { values.optString(it) })
            ?: error("Unvollständiger Gerätestatus; kein Schaltzustand angenommen")
    }

    /**
     * Only devices that EXPLICITLY advertise set_music and set_rgb can use
     * the reverse stream. Unknown device capabilities never trigger a
     * speculative Music-Mode handshake against a real lamp.
     */
    private val musicChannels = ConcurrentHashMap<String, YeelightMusicChannel>()

    fun supportsMusicMode(device: YeelightDevice): Boolean =
        "set_music" in device.methods && "set_rgb" in device.methods

    @Synchronized
    fun startMusicMode(device: YeelightDevice): Boolean {
        if (!supportsMusicMode(device)) return false
        require(isLocalIp(device.ip))
        if (musicChannels[device.ip]?.isActive == true) return true
        check(!musicChannels.containsKey(device.ip)) {
            "Alter Music-Mode-Kanal dieser Lampe muss zuerst beendet werden"
        }
        val host = wifiIpv4Address()
            ?: error("Music-Mode benötigt eine lokale WLAN-IPv4-Adresse")
        val channel = YeelightMusicChannel(
            deviceIp = device.ip,
            enableOnDevice = { localIp, port ->
                send(device, "set_music", listOf(1, localIp, port))
            },
            disableOnDevice = {
                send(device, "set_music", listOf(0))
            }
        )
        channel.start(host)
        musicChannels[device.ip] = channel
        return true
    }

    fun musicModeActive(device: YeelightDevice): Boolean =
        musicChannels[device.ip]?.isActive == true

    /** Returns false only if the device is using the normal LAN mode. */
    fun sendMusicRgb(device: YeelightDevice, rgb: Int): Boolean {
        check(!commandGate.isQuarantined(device.ip)) {
            "Music-Mode pausiert: Firmware-LAN-Quarantäne dieser Lampe"
        }
        val channel = musicChannels[device.ip] ?: return false
        channel.sendRgb(rgb)
        return true
    }

    /**
     * Stop live music mode, or explicitly disable a persisted music session
     * after an Android process restart. The old reverse socket is then already
     * gone, but the bulb's firmware might still believe music mode is active.
     * Send at most ONE quota-accounted set_music(0) on explicit recovery.
     */
    @Synchronized
    fun stopMusicMode(device: YeelightDevice) {
        val channel = musicChannels.remove(device.ip)
        if (channel != null) {
            channel.close()
        } else if (supportsMusicMode(device)) {
            send(device, "set_music", listOf(0))
        }
    }

    // For lamps without set_music, music beats still use the normal LAN channel. Never spend the commands
    // needed to stop and restore a lamp on optional visual beat updates.
    private const val MUSIC_STOP_RESTORE_RESERVE = 12
    fun canSendMusicBeat(device: YeelightDevice): Boolean =
        commandGate.remaining(device.ip) > MUSIC_STOP_RESTORE_RESERVE

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
            // client quota exceeded is an *explicit* firmware rejection.
            // A second socket/get_prop only adds pressure to the quota and
            // does not establish whether a previous flow is running.
            if (QuotaRecoveryPolicy.isQuotaRejection(failure.message) ||
                failure is DeviceCommandGate.Deferred
            ) {
                throw IllegalStateException(
                    "Yeelight-LAN-Limit: Stopp nicht ausgeführt oder " +
                        "nicht bestätigt. " + (failure.message ?: "") +
                        " · später erneut versuchen", failure)
            }
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
