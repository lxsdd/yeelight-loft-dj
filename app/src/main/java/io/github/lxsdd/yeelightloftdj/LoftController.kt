package io.github.lxsdd.yeelightloftdj

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private val loftRooms = setOf("Wohnzimmer", "Esszimmer", "Hobbyraum")
val rooms = listOf("Nicht zugeordnet", "Wohnzimmer", "Esszimmer", "Hobbyraum", "Schlafzimmer", "Masterbad", "Gästebad", "Flur", "Andere")

class LoftController(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("local-room-map", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    var devices by mutableStateOf(loadKnown())
        private set
    var assignments by mutableStateOf(devices.associate { it.id to (prefs.getString("room." + it.id, "Nicht zugeordnet") ?: "Nicht zugeordnet") })
        private set
    var status by mutableStateOf("Bereit · lokales WLAN")
        private set
    var busy by mutableStateOf(false)
        private set
    var target by mutableStateOf("Test (1)")
        private set
    var activeScene by mutableStateOf<String?>(null)
        private set
    var lastSeenIps by mutableStateOf<Set<String>>(emptySet())
        private set
    var lastMulticastIps by mutableStateOf<Set<String>>(emptySet())
        private set
    var lastDirectIps by mutableStateOf<Set<String>>(emptySet())
        private set
    var searchDiagnostics by mutableStateOf("Noch keine vergleichende Suche durchgeführt")
        private set
    var aliases by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    private fun loadKnown(): List<YeelightDevice> = try {
        val array = JSONArray(prefs.getString("known-devices", "[]") ?: "[]")
        (0 until array.length()).mapNotNull { i ->
            val obj = array.optJSONObject(i) ?: return@mapNotNull null
            val ip = obj.optString("ip")
            if (!YeelightLan.isLocalIp(ip)) return@mapNotNull null
            val id = obj.optString("id").ifBlank { "ip-" + ip }
            val port = obj.optInt("port", 55443)
            if (port !in 1..65535) return@mapNotNull null
            YeelightDevice(id, ip, port, obj.optString("model", "unknown"),
                obj.optString("methods").split(" ").filter { it.isNotBlank() }.toSet(),
                obj.optString("lanName"))
        }
    } catch (_: Exception) { emptyList() }

    private fun saveKnown() {
        val array = JSONArray()
        devices.forEach { device ->
            array.put(JSONObject().put("id", device.id).put("ip", device.ip)
                .put("port", device.port).put("model", device.model)
                .put("lanName", device.lanName).put("methods", device.methods.joinToString(" ")))
        }
        prefs.edit().putString("known-devices", array.toString()).apply()
    }

    private fun mergeFound(found: List<YeelightDevice>, method: String): String {
        val prior = devices
        val merged = YeelightLan.mergeDevices(prior, found)
        val updatedRooms = assignments.toMutableMap()
        found.forEach { discovered ->
            val previous = prior.firstOrNull { it.id == discovered.id || it.ip == discovered.ip }
            val replacement = merged.firstOrNull { it.id == discovered.id || it.ip == discovered.ip }
            if (previous != null && replacement != null && previous.id != replacement.id) {
                val room = updatedRooms[previous.id] ?: prefs.getString("room." + previous.id, null)
                if (room != null) {
                    updatedRooms[replacement.id] = room
                    prefs.edit().putString("room." + replacement.id, room).apply()
                }
            }
        }
        devices = merged
        assignments = merged.associate { it.id to (updatedRooms[it.id] ?: prefs.getString("room." + it.id, "Nicht zugeordnet") ?: "Nicht zugeordnet") }
        aliases = merged.associate { it.ip to (prefs.getString("alias." + it.ip, "") ?: "") }
        lastSeenIps = found.map { it.ip }.toSet()
        saveKnown()
        return method + ": " + found.size + " bestätigt · " + devices.size + " insgesamt gespeichert"
    }

    fun chooseTarget(value: String) { if (value in setOf("Test (1)", "Loft", "Alle")) target = value }
    fun roomFor(device: YeelightDevice): String = assignments[device.id] ?: "Nicht zugeordnet"
    fun aliasFor(device: YeelightDevice): String =
        (aliases[device.ip] ?: prefs.getString("alias." + device.ip, "")).orEmpty().trim()

    fun displayName(device: YeelightDevice): String =
        aliasFor(device).ifBlank { device.lanName.ifBlank {
            if (YeelightLan.isSpecificModel(device.model)) device.model
            else "Yeelight · " + device.ip.substringAfterLast('.')
        } }

    fun setAlias(device: YeelightDevice, value: String) {
        val clean = value.trim().take(50)
        prefs.edit().putString("alias." + device.ip, clean).apply()
        aliases = aliases + (device.ip to clean)
        status = if (clean.isEmpty()) "Lokalen Anzeigenamen zurückgesetzt" else "Name gespeichert: " + clean
    }

    fun assign(device: YeelightDevice, room: String) {
        if (room !in rooms) return
        assignments = assignments + (device.id to room)
        prefs.edit().putString("room." + device.id, room).apply()
    }

    fun discover() = task("Suche Yeelights automatisch (WLAN + LAN) …") {
        val (multicast, direct) = withContext(Dispatchers.IO) {
            val udp = YeelightLan.discover(app)
            // Keep the two discovery results separate for diagnosis.
            val tcp = try { YeelightLan.deepDiscover() } catch (_: Exception) { emptyList() }
            Pair(udp, tcp)
        }
        lastMulticastIps = multicast.map { it.ip }.toSet()
        lastDirectIps = direct.map { it.ip }.toSet()
        val both = lastMulticastIps.intersect(lastDirectIps)
        val directOnly = lastDirectIps - lastMulticastIps
        searchDiagnostics = "Multicast: " + lastMulticastIps.size +
            " · Direkt-LAN: " + lastDirectIps.size +
            " · Beide: " + both.size +
            " · Nur direkt: " + directOnly.size
        val merged = YeelightLan.mergeDevices(multicast, direct)
        mergeFound(merged, "Kombinierte Suche")
    }

    fun deepDiscover() = task("Intensivsuche: prüfe lokale WLAN-Adressen …") {
        val found = withContext(Dispatchers.IO) { YeelightLan.deepDiscover() }
        lastDirectIps = found.map { it.ip }.toSet()
        searchDiagnostics = "Intensivsuche: " + lastDirectIps.size +
            " über TCP bestätigt · Multicast zuletzt: " + lastMulticastIps.size
        mergeFound(found, "Intensivsuche")
    }

    fun addManual(ip: String) {
        val address = ip.trim()
        if (!YeelightLan.isLocalIp(address)) {
            status = "Bitte eine lokale IPv4-Adresse eingeben."
            return
        }
        val device = YeelightLan.manual(address)
        if (devices.none { it.ip == address }) {
            devices = YeelightLan.mergeDevices(devices, listOf(device))
            assignments = assignments + (device.id to (prefs.getString("room." + device.id, "Nicht zugeordnet") ?: "Nicht zugeordnet"))
            saveKnown()
        }
        status = "IP gespeichert (noch nicht geprüft): " + address
    }

    fun forget(device: YeelightDevice) {
        devices = devices.filterNot { it.id == device.id }
        assignments = assignments - device.id
        lastSeenIps = lastSeenIps - device.ip
        lastMulticastIps = lastMulticastIps - device.ip
        lastDirectIps = lastDirectIps - device.ip
        prefs.edit().remove("room." + device.id).remove("alias." + device.ip).apply()
        saveKnown()
        status = "Gerät aus dieser App entfernt"
    }

    private fun selected() = when (target) {
        "Test (1)" -> devices.take(1)
        "Alle" -> devices
        else -> devices.filter { roomFor(it) in loftRooms }
    }
    fun selectedCount() = selected().size

    fun play(scene: LoftScene) {
        val selectedDevices = selected()
        if (selectedDevices.isEmpty()) {
            status = "Zuerst Geräte suchen und ggf. Räume zuordnen"
            return
        }
        task("Starte " + scene.name + " …") {
            val errors = withContext(Dispatchers.IO) {
                selectedDevices.mapIndexed { i, device -> async {
                    try { YeelightLan.play(device, scene.expression(i)); null }
                    catch (ex: Exception) { device.ip + ": " + (ex.message ?: "Fehler") }
                } }.awaitAll().filterNotNull()
            }
            activeScene = if (errors.size == selectedDevices.size) null else scene.id
            if (errors.isEmpty()) scene.name + " läuft auf " + selectedDevices.size + " Geräten"
            else (selectedDevices.size - errors.size).toString() + "/" + selectedDevices.size + " gestartet · " + errors.first()
        }
    }

    fun stop() {
        val selectedDevices = selected()
        if (selectedDevices.isEmpty()) return
        task("Stoppe Farbverläufe …") {
            val failures = withContext(Dispatchers.IO) {
                selectedDevices.map { device -> async {
                    try { YeelightLan.stop(device); false }
                    catch (_: Exception) { true }
                } }.awaitAll().count { it }
            }
            activeScene = null
            if (failures == 0) "Farbverläufe gestoppt"
            else "Stopp erreicht " + (selectedDevices.size - failures) + "/" + selectedDevices.size + " Geräte"
        }
    }

    private fun task(initial: String, operation: suspend () -> String) {
        if (busy) return
        busy = true
        status = initial
        scope.launch {
            try { status = operation() }
            catch (ex: Exception) { status = "Fehler: " + (ex.message ?: ex.javaClass.simpleName) }
            finally { busy = false }
        }
    }
    fun close() = scope.cancel()
}
