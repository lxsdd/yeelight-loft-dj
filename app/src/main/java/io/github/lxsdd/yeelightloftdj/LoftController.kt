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
    private val initialSavedScenes = loadSceneArchive()
    var importedFlows by mutableStateOf(initialSavedScenes.flows)
        private set
    var savedChases by mutableStateOf(initialSavedScenes.chases)
        private set
    var lampOrder by mutableStateOf(loadLampOrder())
        private set
    var runningDeviceIps by mutableStateOf<Set<String>>(emptySet())
        private set
    // Live status is intentionally session-only; cached values from yesterday are not live data.
    var lampSnapshots by mutableStateOf<Map<String, LampSnapshot>>(emptyMap())
        private set


    private fun loadSceneArchive(): SceneArchiveData =
        runCatching {
            val local = prefs.getString("custom-scene-archive", null)
            if (local.isNullOrEmpty()) SceneArchiveData(emptyList(), emptyList())
            else SceneArchive.decode(local)
        }.getOrDefault(SceneArchiveData(emptyList(), emptyList()))

    private fun persistSceneArchive() {
        prefs.edit().putString("custom-scene-archive",
            SceneArchive.encode(SceneArchiveData(importedFlows, savedChases))).apply()
    }

    private fun loadLampOrder(): List<String> =
        runCatching {
            val arr = JSONArray(prefs.getString("lamp-order", "[]") ?: "[]")
            (0 until arr.length()).map { arr.getString(it) }
        }.getOrDefault(emptyList())

    fun orderedDevices(): List<YeelightDevice> = devices.sortedWith(
        compareBy<YeelightDevice> {
            val index = lampOrder.indexOf(it.id)
            if (index >= 0) index else Int.MAX_VALUE
        }.thenBy { it.ip.split(".").lastOrNull()?.toIntOrNull() ?: 0 }.thenBy { it.id }
    )

    fun moveLamp(device: YeelightDevice, delta: Int) {
        if (busy || delta !in listOf(-1, 1)) return
        val ordered = orderedDevices().map { it.id }.toMutableList()
        val index = ordered.indexOf(device.id)
        val destination = index + delta
        if (index < 0 || destination !in ordered.indices) return
        java.util.Collections.swap(ordered, index, destination)
        lampOrder = ordered
        prefs.edit().putString("lamp-order", JSONArray(ordered).toString()).apply()
        status = "Lauflicht-Reihenfolge aktualisiert"
    }

    fun allScenes(): List<LoftScene> = SceneLibrary.scenes + importedFlows

    fun exportSceneArchive(): String = SceneArchive.encode(
        SceneArchiveData(allScenes(), savedChases)
    )

    fun saveChase(name: String, config: ChaseConfig) {
        val clean = name.trim()
        require(clean.isNotEmpty() && clean.length <= 64) { "Bitte einen Szenennamen mit bis zu 64 Zeichen eingeben" }
        require(savedChases.size + importedFlows.size + SceneLibrary.scenes.size < 64) { "Maximal 64 Szenen" }
        savedChases = savedChases + ChasePreset("user-" + java.util.UUID.randomUUID(), clean, config)
        persistSceneArchive()
        status = "Lichtlauf gespeichert: " + clean
    }

    fun deleteChase(preset: ChasePreset) {
        savedChases = savedChases.filterNot { it.id == preset.id }
        persistSceneArchive()
        status = "Eigene Szene entfernt: " + preset.name
    }

    fun importSceneArchive(text: String): String {
        val incoming = SceneArchive.decode(text)
        val existingFlows = allScenes()
        val newFlows = incoming.flows.filterNot { candidate ->
            existingFlows.any { it.name == candidate.name && it.frames == candidate.frames }
        }.distinctBy { it.name to it.frames }.map {
            it.copy(id = "import-" + java.util.UUID.randomUUID())
        }
        val newChases = incoming.chases.filterNot { candidate ->
            savedChases.any { it.name == candidate.name && it.config == candidate.config }
        }.distinctBy { it.name to it.config }.map {
            it.copy(id = "import-" + java.util.UUID.randomUUID())
        }
        require(existingFlows.size + savedChases.size + newFlows.size + newChases.size <= 64) {
            "Die Szenensammlung würde 64 Szenen überschreiten"
        }
        importedFlows = importedFlows + newFlows
        savedChases = savedChases + newChases
        persistSceneArchive()
        return "${newFlows.size} Farbverläufe und ${newChases.size} Lichtläufe importiert"
    }

    fun report(message: String) { status = message }

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
                if (previous.id in lampOrder) {
                    lampOrder = lampOrder.map { if (it == previous.id) replacement.id else it }.distinct()
                    prefs.edit().putString("lamp-order", JSONArray(lampOrder).toString()).apply()
                }
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
            " · Nur direkt: " + directOnly.size +
            "\nUDP-Antwortpakete: " + YeelightLan.lastSearchPackets +
            " · davon nicht auswertbar: " + YeelightLan.lastSearchInvalidPackets
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
        lampSnapshots = lampSnapshots - device.ip
        lampOrder = lampOrder - device.id
        prefs.edit().putString("lamp-order", JSONArray(lampOrder).toString()).apply()
        prefs.edit().remove("room." + device.id).remove("alias." + device.ip).apply()
        saveKnown()
        status = "Gerät aus dieser App entfernt"
    }


    fun snapshotFor(device: YeelightDevice): LampSnapshot? = lampSnapshots[device.ip]

    fun refreshLamp(device: YeelightDevice) = task("Lese Status von " + displayName(device) + " …") {
        val current = withContext(Dispatchers.IO) { YeelightLan.readStatus(device) }
        lampSnapshots = lampSnapshots + (device.ip to current)
        displayName(device) + ": " + if (current.isOn) "Ein · " + current.brightness + "%" else "Aus"
    }

    fun controlLamp(device: YeelightDevice, command: LampCommand, description: String) =
        task(description + " …") {
            val current = withContext(Dispatchers.IO) {
                YeelightLan.manualControl(device, command)
                runCatching { YeelightLan.readStatus(device) }.getOrNull()
            }
            if (current != null) lampSnapshots = lampSnapshots + (device.ip to current)
            else lampSnapshots = lampSnapshots - device.ip
            displayName(device) + ": " + description +
                if (current != null) " · bestätigt" else " · Befehl akzeptiert, Status unbekannt"
        }

    fun identifyLamp(device: YeelightDevice) {
        if (activeScene != null) {
            status = "Lichtshow zuerst stoppen, um den Lampenzustand nicht zu verändern"
            return
        }
        task("Identifiziere " + displayName(device) + " …") {
            withContext(Dispatchers.IO) { YeelightLan.identify(device) }
            val restored = runCatching { withContext(Dispatchers.IO) { YeelightLan.readStatus(device) } }.getOrNull()
            if (restored != null) lampSnapshots = lampSnapshots + (device.ip to restored)
            else lampSnapshots = lampSnapshots - device.ip
            displayName(device) + ": kurzer Lichtimpuls abgeschlossen · Zustand wiederhergestellt"
        }
    }

    fun powerTarget(on: Boolean) {
        val chosen = selected()
        if (chosen.isEmpty()) {
            status = "Bitte zuerst den Zielbereich mit Lampen belegen"
            return
        }
        task(if (on) "Schalte Zielbereich ein …" else "Schalte Zielbereich aus …") {
            val errors = withContext(Dispatchers.IO) {
                chosen.map { device -> async {
                    try { YeelightLan.manualControl(device, LampCommands.power(on)); null }
                    catch (e: Exception) { displayName(device) + ": " + (e.message ?: "Fehler") }
                } }.awaitAll().filterNotNull()
            }
            lampSnapshots = lampSnapshots - chosen.map { it.ip }.toSet()
            activeScene = null
            val successful = chosen.size - errors.size
            successful.toString() + "/" + chosen.size + " Lampen " +
                (if (on) "eingeschaltet" else "ausgeschaltet") +
                (if (errors.isEmpty()) "" else " · " + errors.first())
        }
    }

    private fun selected() = chooseDevices(target)
    private fun chooseDevices(choice: String): List<YeelightDevice> = when (choice) {
        "Test (1)" -> orderedDevices().take(1)
        "Alle" -> orderedDevices()
        "Loft" -> orderedDevices().filter { roomFor(it) in loftRooms }
        else -> emptyList()
    }
    fun selectedCount() = selected().size


    /**
     * Keep all running bulbs tracked, even if target changes later. Starting a
     * new scene always stops the old loop first. Each bulb gets one native flow.
     */
    private fun startFlow(
        id: String,
        name: String,
        chosen: List<YeelightDevice>,
        expression: (index: Int, count: Int) -> String,
    ) {
        if (chosen.isEmpty()) {
            status = "Für diese Szene sind keine Lampen ausgewählt"
            return
        }
        task("Starte " + name + " auf " + chosen.size + " Lampen …") {
            val previous = devices.filter { it.ip in runningDeviceIps }
            if (previous.isNotEmpty()) {
                val stopFailures = withContext(Dispatchers.IO) {
                    previous.map { device -> async {
                        try { YeelightLan.stop(device); null } catch (e: Exception) { device.ip }
                    } }.awaitAll().filterNotNull()
                }
                if (stopFailures.isNotEmpty()) {
                    runningDeviceIps = stopFailures.toSet()
                    error("Laufende Szene konnte auf ${stopFailures.size} Lampen nicht beendet werden")
                }
                runningDeviceIps = emptySet()
                activeScene = null
            }
            val results = withContext(Dispatchers.IO) {
                chosen.mapIndexed { i, device -> async {
                    try { YeelightLan.play(device, expression(i, chosen.size)); device.ip to null }
                    catch (ex: Exception) { device.ip to (ex.message ?: "Fehler") }
                } }.awaitAll()
            }
            val successful = results.filter { it.second == null }.map { it.first }.toSet()
            runningDeviceIps = successful
            activeScene = if (successful.isNotEmpty()) id else null
            val errors = results.filter { it.second != null }
            if (errors.isEmpty()) name + " läuft auf " + successful.size + " Lampen"
            else successful.size.toString() + "/" + chosen.size + " gestartet · " +
                errors.first().first + ": " + errors.first().second
        }
    }

    fun play(scene: LoftScene) =
        startFlow(scene.id, scene.name, selected()) { index, _ -> scene.expression(index) }

    fun playChase(config: ChaseConfig, choice: String, name: String = "Lichtlauf") {
        val chosen = chooseDevices(choice)
        if (chosen.size > 24) {
            status = "Der Lichtlauf unterstützt derzeit bis zu 24 Lampen"
            return
        }
        startFlow("chase", name, chosen) { index, count ->
            ChaseCompiler.expression(config, index, count)
        }
    }

    fun stop() {
        val selectedDevices = devices.filter { it.ip in runningDeviceIps }
        if (selectedDevices.isEmpty()) {
            activeScene = null
            status = "Keine gestartete Lichtshow bekannt"
            return
        }
        task("Stoppe Farbverläufe auf " + selectedDevices.size + " Lampen …") {
            val failed = withContext(Dispatchers.IO) {
                selectedDevices.map { device -> async {
                    try { YeelightLan.stop(device); null }
                    catch (ex: Exception) { device.ip }
                } }.awaitAll().filterNotNull().toSet()
            }
            runningDeviceIps = failed
            if (failed.isEmpty()) activeScene = null
            if (failed.isEmpty()) "Alle Farbverläufe gestoppt"
            else "Stopp erreicht " + (selectedDevices.size - failed.size) + "/" +
                selectedDevices.size + " · " + failed.size + " nicht bestätigt"
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
