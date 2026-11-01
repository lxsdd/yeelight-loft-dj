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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject

private val loftRooms = setOf("Wohnzimmer", "Esszimmer", "Hobbyraum")
data class SceneLampResult(val name: String, val outcome: String, val message: String)
val rooms = listOf("Nicht zugeordnet", "Wohnzimmer", "Esszimmer", "Hobbyraum", "Schlafzimmer", "Masterbad", "Gästebad", "Flur", "Andere")

class LoftController(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("local-room-map", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var microphone: MicrophoneCapture? = null
    private val internalAudioSink: (MusicFrame) -> Unit = { frame ->
        if (!microphoneRunning && InternalAudioBridge.running) consumeMusicFrame(frame)
    }
    private var musicSending = false
    private var musicStopPending = false
    private var lastMusicCommandAt = Long.MIN_VALUE / 2
    private var musicDeviceSession = prefs.getBoolean("music-light-session", false)
    private var musicColorBeat = 0L


    var devices by mutableStateOf(loadKnown())
        private set
    var assignments by mutableStateOf(devices.associate { it.id to (prefs.getString("room." + it.id, "Nicht zugeordnet") ?: "Nicht zugeordnet") })
        private set
    var roomNames by mutableStateOf(loadRooms())
        private set
    var customSelectedIds by mutableStateOf(loadSelection())
        private set
    var lastSceneReport by mutableStateOf<List<SceneLampResult>>(emptyList())
        private set
    var lastRecoveryFailures by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    var status by mutableStateOf("Bereit · lokales WLAN")
        private set
    var microphoneRunning by mutableStateOf(false)
        private set
    var musicFrame by mutableStateOf<MusicFrame?>(null)
        private set
    var musicBeatCount by mutableStateOf(0L)
        private set
    var musicLightArmed by mutableStateOf(false)
        private set
    var musicLightStatus by mutableStateOf(
        if (musicDeviceSession) "Frühere Musiklicht-Sitzung erkannt · bitte Beleuchtung wiederherstellen"
        else "Musik-Lichtsteuerung nicht aktiviert"
    )
        private set
    var busy by mutableStateOf(false)
        private set
    var target by mutableStateOf(prefs.getString("selected-target", "Auswahl")
        ?.takeIf { it.isNotBlank() } ?: "Auswahl")
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
    var directedUdpDiagnostics by mutableStateOf("")
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
    var runningDeviceIps by mutableStateOf(loadRunningIps())
        private set
    var restoreAfterStop by mutableStateOf(prefs.getBoolean("restore-after-stop", false))
        private set
    private var recoveryJournal = SceneRecovery.decode(prefs.getString("pending-scene-recovery", null))

    init {
        InternalAudioBridge.attach(internalAudioSink) {
            musicFrame = null
            if (musicLightArmed) disarmMusicLights()
        }
        // Migrate old Test (1) and Loft targets without discarding lamp/room data.
        // No lights are modified by this one-time settings-only migration.
        val legacy = target
        val revised = when (legacy) {
            "Test (1)" -> devices.firstOrNull()?.let { "Lampe:" + it.id } ?: "Auswahl"
            "Loft" -> {
                val priorMembers = loadLoftMembers() ?: devices
                    .filter { assignments[it.id] in setOf("Wohnzimmer", "Esszimmer", "Hobbyraum") }
                    .map { it.id }.toSet()
                val combined = customSelectedIds + priorMembers
                check(prefs.edit().putString("selected-lamp-ids",
                    JSONArray(combined.toList()).toString()).commit()) {
                    "Bisherige Lampengruppe konnte nicht übernommen werden"
                }
                customSelectedIds = combined
                "Auswahl"
            }
            else -> legacy
        }
        if (revised != legacy) {
            check(prefs.edit().putString("selected-target", revised).commit()) {
                "Zielauswahl konnte nicht migriert werden"
            }
            target = revised
        }
    }
    // Live status is intentionally session-only; cached values from yesterday are not live data.
    var lampSnapshots by mutableStateOf<Map<String, LampSnapshot>>(emptyMap())
        private set



    private fun loadRooms(): List<String> =
        runCatching {
            val stored = prefs.getString("room-list", null)
            if (stored == null) rooms
            else {
                val array = JSONArray(stored)
                (listOf("Nicht zugeordnet") + (0 until array.length())
                    .map { array.getString(it) }.filter { it != "Nicht zugeordnet" })
                    .distinct().take(32)
            }
        }.getOrDefault(rooms)

    private fun loadSelection(): Set<String> =
        runCatching {
            val array = JSONArray(prefs.getString("selected-lamp-ids", "[]") ?: "[]")
            (0 until array.length()).map { array.getString(it) }.toSet()
        }.getOrDefault(emptySet())

    private fun loadLoftMembers(): Set<String>? = runCatching {
        if (!prefs.getBoolean("loft-members-customized", false)) null
        else {
            val arr = JSONArray(prefs.getString("loft-member-ids", "[]") ?: "[]")
            (0 until arr.length()).map { arr.getString(it) }.toSet()
        }
    }.getOrNull()

    private fun persistRoomNames() {
        check(prefs.edit().putString("room-list", JSONArray(roomNames).toString()).commit()) {
            "Raumliste konnte nicht gespeichert werden"
        }
    }

    fun addRoom(name: String): Boolean {
        val clean = name.trim()
        if (!canOrganizeLamps || clean.isEmpty() || clean.length > 40 ||
            clean.any { it.isISOControl() } || roomNames.any { it.equals(clean, true) } ||
            roomNames.size >= 32) {
            status = "Raumname unzulässig, bereits vorhanden oder Lichtshow aktiv"
            return false
        }
        roomNames = roomNames + clean
        persistRoomNames()
        status = "Raum hinzugefügt: " + clean
        return true
    }

    fun renameRoom(old: String, fresh: String): Boolean {
        val clean = fresh.trim()
        if (!canOrganizeLamps || old == "Nicht zugeordnet" ||
            old !in roomNames || clean.isEmpty() || clean.length > 40 ||
            clean.any { it.isISOControl() } ||
            roomNames.any { it != old && it.equals(clean, true) }) {
            status = "Raum konnte nicht umbenannt werden"
            return false
        }
        val editor = prefs.edit()
        assignments.forEach { (id, room) ->
            if (room == old) editor.putString("room." + id, clean)
        }
        val updated = roomNames.map { if (it == old) clean else it }
        val revisedTarget = TargetReference.roomRenamed(target, old, clean)
        editor.putString("room-list", JSONArray(updated).toString())
        editor.putString("selected-target", revisedTarget)
        check(editor.commit()) { "Raumänderungen konnten nicht gespeichert werden" }
        assignments = assignments.mapValues { (_, v) -> if (v == old) clean else v }
        roomNames = updated
        target = revisedTarget
        status = "Raum umbenannt: " + clean
        return true
    }

    fun deleteRoom(name: String): Boolean {
        if (!canOrganizeLamps || name == "Nicht zugeordnet" ||
            name !in roomNames) return false
        val editor = prefs.edit()
        assignments.forEach { (id, room) ->
            if (room == name) editor.putString("room." + id, "Nicht zugeordnet")
        }
        val updated = roomNames - name
        val revisedTarget = TargetReference.roomRemoved(target, name)
        editor.putString("room-list", JSONArray(updated).toString())
        editor.putString("selected-target", revisedTarget)
        check(editor.commit()) { "Raum konnte nicht entfernt werden" }
        roomNames = updated
        target = revisedTarget
        assignments = assignments.mapValues { (_, room) ->
            if (room == name) "Nicht zugeordnet" else room
        }
        status = "Raum entfernt; betroffene Lampen sind nicht zugeordnet"
        return true
    }

    fun assignMany(ids: Set<String>, room: String): Int {
        if (!canOrganizeLamps || room !in roomNames) return 0
        val eligible = devices.filter { it.id in ids }
        val editor = prefs.edit()
        eligible.forEach { editor.putString("room." + it.id, room) }
        check(editor.commit()) { "Raumzuordnung konnte nicht gespeichert werden" }
        assignments = assignments + eligible.associate { it.id to room }
        status = eligible.size.toString() + " Lampen → " + room
        return eligible.size
    }

    fun setCustomSelection(id: String, enabled: Boolean) {
        if (id !in devices.map { it.id }) return
        val updated = if (enabled) customSelectedIds + id else customSelectedIds - id
        check(prefs.edit().putString("selected-lamp-ids", JSONArray(updated.toList()).toString()).commit()) {
            "Auswahl konnte nicht gespeichert werden"
        }
        customSelectedIds = updated
    }

    fun targetOptions(): List<String> =
        listOf("Auswahl", "Alle") +
        roomNames.filter { it != "Nicht zugeordnet" }.map { "Raum:$it" } +
        orderedDevices().map { "Lampe:" + it.id }

    fun targetDescription(choice: String): String = when {
        choice == "Alle" ->
            "Alle gespeicherten Lampen, auch offline. Beim Start werden Fehler je Lampe angezeigt."
        choice == "Auswahl" -> "Deine " + customSelectedIds.size +
            " ausgewählten Lampen. Offline-Lampen können separat fehlschlagen."
        else -> ""
    }

    fun targetLabel(value: String): String = when {
        value.startsWith("Raum:") -> value.removePrefix("Raum:")
        value.startsWith("Lampe:") -> {
            devices.firstOrNull { it.id == value.removePrefix("Lampe:") }
                ?.let(::displayName) ?: "Nicht bekannte Lampe"
        }
        value == "Auswahl" -> "Eigene Lampenauswahl"
        else -> value
    }

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

    /**
     * Never drop an unconfirmed previous lighting state. Android can be killed
     * between committing a recovery snapshot and tracking the started IPs.
     * A recovered music session must also not silently unlock manual edits.
     */
    val recoveryPending: Boolean
        get() = LampOperationGate.needsRecovery(
            activeScene, runningDeviceIps, recoveryJournal.keys, musicDeviceSession
        )

    val pendingRecoveryCount: Int
        get() = LampOperationGate.pendingIps(runningDeviceIps, recoveryJournal.keys).size

    val canRetryRecovery: Boolean
        get() = !busy && !musicStopPending && !musicSending

    val recoveryExplanation: String
        get() = when {
            musicStopPending -> "Musiklicht wird bereits gestoppt und wiederhergestellt."
            pendingRecoveryCount > 0 ->
                "$pendingRecoveryCount Lampe(n) konnten nicht sicher gestoppt bzw. " +
                    "wiederhergestellt werden. Nur diese Geräte bleiben geschützt; " +
                    "andere Lampen kannst du weiterhin identifizieren und für Shows nutzen."
            musicDeviceSession -> "Frühere Musiklicht-Sitzung benötigt eine Wiederherstellung."
            activeScene != null -> "Lichtshow aktiv. Betroffene Lampen vor dem Identifizieren stoppen."
            else -> ""
        }

    /** Editing local groups and the lamp order never sends bulb commands. */
    val canOrganizeLamps: Boolean
        get() = LampOperationGate.canEditConfiguration(
            busy, musicDeviceSession, musicStopPending || musicSending
        )

    fun canIdentifyLamp(device: YeelightDevice): Boolean =
        LampOperationGate.canIdentifyLamp(
            busy, device.ip, runningDeviceIps, recoveryJournal.keys,
            musicDeviceSession, musicStopPending || musicSending
        ) &&
        // Identification requires a safely restorable power/brightness pulse.
        // Do not present a clickable identify action on a device explicitly
        // advertising that either method is unsupported.
        DeviceCapabilities.evidence(device, "set_power") != CapabilityEvidence.UNSUPPORTED &&
        DeviceCapabilities.evidence(device, "set_bright") != CapabilityEvidence.UNSUPPORTED

    fun pendingLampDescriptions(): List<String> =
        LampOperationGate.pendingIps(runningDeviceIps, recoveryJournal.keys)
            .sorted().map { ip ->
                val device = devices.firstOrNull { it.ip == ip }
                val display = device?.let(::displayName) ?: "Nicht mehr gespeicherte Lampe"
                val reason = lastRecoveryFailures[ip]
                display + " · " + ip.substringAfterLast('.') +
                    if (reason.isNullOrBlank()) " · Zustand ungeklärt"
                    else " · " + reason.take(120)
            }

    /** Position is zero-based, all changes are committed before updating the UI. */
    fun placeLampAt(device: YeelightDevice, position: Int): Boolean {
        if (!canOrganizeLamps) {
            status = "Zum Sortieren zuerst die laufende Lichtshow stoppen"
            return false
        }
        val current = orderedDevices().map { it.id }
        if (device.id !in current || position !in current.indices) {
            status = "Lampe oder Position nicht mehr vorhanden – Geräte neu prüfen"
            return false
        }
        val updated = LampOrder.relocate(current, device.id, position)
        if (!prefs.edit().putString("lamp-order", JSONArray(updated).toString()).commit()) {
            status = "Lauflicht-Reihenfolge konnte nicht gespeichert werden"
            return false
        }
        lampOrder = updated
        status = displayName(device) + " ist jetzt auf Lauflicht-Platz " + (position + 1)
        return true
    }

    /** Commit a complete drag-and-drop order only after the drag finishes. */
    fun saveDraggedOrder(orderedIds: List<String>): Boolean {
        if (!canOrganizeLamps) {
            status = "Lichtshow zuerst stoppen, bevor die Reihenfolge verändert wird"
            return false
        }
        val current = orderedDevices().map { it.id }
        if (orderedIds.size != current.size || orderedIds.toSet() != current.toSet() ||
            orderedIds.distinct().size != orderedIds.size
        ) {
            status = "Geräteliste hat sich geändert – Sortierung erneut öffnen"
            return false
        }
        if (!prefs.edit().putString("lamp-order", JSONArray(orderedIds).toString()).commit()) {
            status = "Reihenfolge konnte nicht dauerhaft gespeichert werden"
            return false
        }
        lampOrder = orderedIds
        status = "Lauflicht-Reihenfolge gespeichert"
        return true
    }

    fun moveLamp(device: YeelightDevice, delta: Int) {
        if (delta !in listOf(-1, 1)) return
        val index = orderedDevices().indexOfFirst { it.id == device.id }
        if (index < 0) return
        placeLampAt(device, index + delta)
    }

    fun allScenes(): List<LoftScene> = SceneLibrary.scenes + importedFlows

    fun exportSceneArchive(): String = SceneArchive.encode(
        SceneArchiveData(allScenes(), savedChases)
    )

    fun exportSingleFlow(scene: LoftScene): String =
        SceneArchive.encode(SceneArchiveData(listOf(scene), emptyList()))

    fun exportSingleChase(preset: ChasePreset): String =
        SceneArchive.encode(SceneArchiveData(emptyList(), listOf(preset)))

    fun configureRestoreAfterStop(enabled: Boolean) {
        // The choice is frozen for the full duration of a running scene.
        if (!canOrganizeLamps) return
        restoreAfterStop = enabled
        prefs.edit().putBoolean("restore-after-stop", enabled).apply()
    }

    private fun loadRunningIps(): Set<String> = runCatching {
        val arr = JSONArray(prefs.getString("running-flow-ips", "[]") ?: "[]")
        (0 until arr.length()).map { arr.getString(it) }.filter(YeelightLan::isLocalIp).toSet()
    }.getOrDefault(emptySet())

    private fun setRunningIps(ips: Set<String>) {
        check(prefs.edit().putString("running-flow-ips", JSONArray(ips.toList()).toString()).commit()) {
            "Aktiven Lichtlauf konnte nicht lokal gesichert werden"
        }
        runningDeviceIps = ips
    }

    private fun setRecoveryJournal(entries: Map<String, LampSnapshot>) {
        check(prefs.edit().putString("pending-scene-recovery", SceneRecovery.encode(entries)).commit()) {
            "Wiederherstellungsdaten konnten nicht lokal gesichert werden"
        }
        recoveryJournal = entries
    }

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
                val revisedTarget = TargetReference.deviceRenamed(
                    target, previous.id, replacement.id
                )
                if (revisedTarget != target) {
                    check(prefs.edit().putString("selected-target", revisedTarget).commit()) {
                        "Geräte-Ziel konnte nicht auf neue ID übertragen werden"
                    }
                    target = revisedTarget
                }
                if (previous.id in lampOrder) {
                    lampOrder = lampOrder.map { if (it == previous.id) replacement.id else it }.distinct()
                    prefs.edit().putString("lamp-order", JSONArray(lampOrder).toString()).apply()
                }
                if (previous.id in customSelectedIds) {
                    val revised = (customSelectedIds - previous.id) + replacement.id
                    check(prefs.edit().putString("selected-lamp-ids",
                        JSONArray(revised.toList()).toString()).commit()) {
                        "Geräteauswahl konnte nicht übertragen werden"
                    }
                    customSelectedIds = revised
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

    fun chooseTarget(value: String) {
        if (value !in targetOptions()) {
            status = "Ziel ist nicht mehr verfügbar. Auswahl prüfen."
            return
        }
        if (!prefs.edit().putString("selected-target", value).commit()) {
            status = "Lampen-Ziel konnte nicht gespeichert werden"
            return
        }
        target = value
    }
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
        if (!canOrganizeLamps || room !in roomNames) return
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
        directedUdpDiagnostics = ""
        lastMulticastIps = multicast.map { it.ip }.toSet()
        lastDirectIps = direct.map { it.ip }.toSet()
        val both = lastMulticastIps.intersect(lastDirectIps)
        val directOnly = lastDirectIps - lastMulticastIps
        searchDiagnostics = "Multicast: " + lastMulticastIps.size +
            " · Direkt-LAN: " + lastDirectIps.size +
            " · Beide: " + both.size +
            " · Nur direkt: " + directOnly.size +
            "\nUDP-Antwortpakete: " + YeelightLan.lastSearchPackets +
            " · davon nicht auswertbar: " + YeelightLan.lastSearchInvalidPackets +
            "\nWLAN-Sendeadresse: " + YeelightLan.lastSearchSourceAddress +
            " · Treffer je Suchrunde: " + YeelightLan.lastSearchRoundUnique.joinToString("/") +
            " · Absender-/Location-Abweichungen: " + YeelightLan.lastSearchSourceMismatches
        val merged = YeelightLan.mergeDevices(multicast, direct)
        mergeFound(merged, "Kombinierte Suche")
    }

    fun missingMulticastCount(): Int =
        (lastDirectIps - lastMulticastIps).size

    /** Expanded read-only A/B trial with 12 send windows instead of the usual 5. */
    fun extendedMulticastDiagnostic() =
        task("12-Runden-Multicast-Diagnose, nur Netzwerkverkehr …") {
            val replies = withContext(Dispatchers.IO) {
                YeelightLan.discover(app, rounds = 12, listenMs = 1600L)
            }
            lastMulticastIps = replies.map { it.ip }.toSet()
            val both = lastMulticastIps.intersect(lastDirectIps).size
            val directOnly = lastDirectIps - lastMulticastIps
            searchDiagnostics = "Erweiterte UDP-Diagnose: " + lastMulticastIps.size +
                " eindeutige Multicast-Antworter in 12 Suchrunden" +
                "\nTCP bestätigt: " + lastDirectIps.size +
                " · Beide: " + both + " · Nur TCP: " + directOnly.size +
                "\nPro Runde UDP-Antworter: " +
                YeelightLan.lastSearchRoundUnique.joinToString("/") +
                "\nAntwortpakete insgesamt: " + YeelightLan.lastSearchPackets +
                " · nicht lesbar: " + YeelightLan.lastSearchInvalidPackets +
                "\nAbsender-IP: " + YeelightLan.lastSearchSourceAddress +
                " · Absender/Location-Abweichungen: " +
                YeelightLan.lastSearchSourceMismatches
            mergeFound(replies, "12-Runden-Multicast-Gegenprobe")
            searchDiagnostics
        }

    fun discoveryDetail(device: YeelightDevice): String {
        val rounds = YeelightLan.lastSearchRoundResponders
        val responseRounds = rounds.count { device.ip in it }
        val packets = YeelightLan.lastSearchRepliesPerIp[device.ip] ?: 0
        val tcp = if (device.ip in lastDirectIps) "TCP bestätigt" else "TCP nicht geprüft"
        val udp = if (device.ip in lastMulticastIps) "UDP geantwortet"
            else "UDP keine Antwort"
        return tcp + " · " + udp + " · " + responseRounds + "/" + rounds.size +
            " Runden · " + packets + " Antwortpakete"
    }

    fun compareDirectedUdp() {
        val missing = devices.filter {
            it.ip in lastDirectIps && it.ip !in lastMulticastIps
        }
        if (missing.isEmpty()) {
            status = "Erst eine kombinierte Suche mit Direkt-LAN-Treffern durchführen"
            return
        }
        task("Experimentelle UDP-Gegenprobe bei " + missing.size + " Lampen …") {
            val replied = withContext(Dispatchers.IO) { YeelightLan.directedUdpProbe(missing) }
            directedUdpDiagnostics = "Gezielte UDP-Gegenprobe: " + replied.size + "/" +
                missing.size + " antworten · " +
                if (replied.isEmpty()) "Kein eindeutiger Rückschluss möglich (Multicast ist das Standardverfahren)"
                else "Mindestens eine Lampe beantwortet direktes UDP, aber nicht Multicast"
            directedUdpDiagnostics
        }
    }

    fun diagnoseDeskLamp(ip: String) {
        if (!YeelightLan.isLocalIp(ip.trim())) {
            status = "Für Mi Desk Lamp 1S bitte die lokale IP aus der FRITZ!Box eintragen"
            return
        }
        task("Prüfe Desk Lamp 1S über Yeelight und Xiaomi MiIO, nur lesend …") {
            withContext(Dispatchers.IO) { MiDeskLampDiagnostics.diagnostic(ip.trim()) }
        }
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
        if (device.ip in LampOperationGate.pendingIps(runningDeviceIps, recoveryJournal.keys)) {
            status = "Zustand dieser Lampe noch ungeklärt. Erst Wiederherstellung prüfen."
            return
        }
        val revisedTarget = TargetReference.deviceRemoved(target, device.id)
        if (revisedTarget != target) {
            check(prefs.edit().putString("selected-target", revisedTarget).commit()) {
                "Geräte-Ziel konnte nicht zurückgesetzt werden"
            }
            target = revisedTarget
        }
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

    /** Read-only real-world check without trying an unsupported colour command. */
    fun inspectLamp(device: YeelightDevice) = task("Prüfe " + displayName(device) + " nur lesend …") {
        val live = withContext(Dispatchers.IO) { YeelightLan.readStatus(device) }
        lampSnapshots = lampSnapshots + (device.ip to live)
        val known = DeviceCapabilities.capabilities(device).count {
            it.evidence == CapabilityEvidence.CONFIRMED
        }
        val capabilityText = if (device.methods.isEmpty()) {
            "SUPPORT-Liste nicht bekannt: volle Fähigkeiten erst nach Multicast-Suche prüfbar"
        } else "$known Befehle aus Yeelight-SUPPORT bestätigt"
        displayName(device) + " erreichbar · " +
            if (live.isOn) "Ein · " + live.brightness + "% · " + capabilityText
            else "Aus · " + capabilityText
    }

    fun controlLamp(device: YeelightDevice, command: LampCommand, description: String) {
        if (!canIdentifyLamp(device)) {
            status = "Diese Lampe ist noch in einer Lichtshow/Wiederherstellung. " +
                "Bitte zuerst gezielt stoppen."
            return
        }
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
    }

    fun identifyLamp(device: YeelightDevice) {
        if (!canIdentifyLamp(device)) {
            status = if (busy) "Bitte den laufenden Vorgang abschließen"
                else "Nur diese Lampe ist noch in einer Lichtshow/Wiederherstellung. " +
                    "Andere Lampen können identifiziert werden."
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
            if (recoveryPending && !musicDeviceSession) haltRunning(false)
            val pending = LampOperationGate.pendingIps(runningDeviceIps, recoveryJournal.keys)
            val permitted = chosen.filterNot { it.ip in pending }
            val skipped = chosen.size - permitted.size
            val gate = Semaphore(3)
            val errors = withContext(Dispatchers.IO) {
                permitted.map { device -> async {
                    gate.withPermit {
                        try { YeelightLan.manualControl(device, LampCommands.power(on)); null }
                        catch (e: Exception) { displayName(device) + ": " + (e.message ?: "Fehler") }
                    }
                } }.awaitAll().filterNotNull()
            }
            lampSnapshots = lampSnapshots - permitted.map { it.ip }.toSet()
            if (pending.isEmpty()) activeScene = null
            val successful = permitted.size - errors.size
            successful.toString() + "/" + chosen.size + " Lampen " +
                (if (on) "eingeschaltet" else "ausgeschaltet") +
                (if (skipped > 0) " · " + skipped + " wegen offener Wiederherstellung geschützt" else "") +
                (if (errors.isEmpty()) "" else " · " + errors.first())
        }
    }

    private fun selected() = chooseDevices(target)
    private fun chooseDevices(choice: String): List<YeelightDevice> = when {
        choice == "Alle" -> orderedDevices()
        choice == "Auswahl" -> orderedDevices().filter { it.id in customSelectedIds }
        choice.startsWith("Raum:") -> orderedDevices().filter {
            roomFor(it) == choice.removePrefix("Raum:")
        }
        choice.startsWith("Lampe:") -> orderedDevices().filter {
            it.id == choice.removePrefix("Lampe:")
        }
        else -> emptyList()
    }
    fun selectedCount() = selected().size
    fun previewCount(choice: String): Int = chooseDevices(choice).size


    /**
     * Stop every tracked lamp, then restore the previously captured static state.
     * Unconfirmed operations stay in the recovery journal for an explicit retry.
     */
    private suspend fun haltRunning(allowTargetFallback: Boolean): String {
        // A saved recovery journal may outlive its running-IP marker (crash).
        // Recreate missing tracked devices by private IP rather than trapping
        // the user behind an impossible-to-clear global disabled state.
        val pendingIps = LampOperationGate.pendingIps(runningDeviceIps, recoveryJournal.keys)
        val chosen = if (pendingIps.isNotEmpty()) {
            pendingIps.map { ip ->
                devices.firstOrNull { it.ip == ip } ?: YeelightLan.manual(ip)
            }
        } else if (allowTargetFallback) selected() else emptyList()
        if (chosen.isEmpty()) return "Keine Lampen zum Stoppen ausgewählt"
        var stopped = 0
        val outcomes = withContext(Dispatchers.IO) {
            val limit = Semaphore(3)
            chosen.map { device -> async {
                limit.withPermit {
                    val issue = runCatching {
                        if (!musicDeviceSession) YeelightLan.stop(device)
                        recoveryJournal[device.ip]?.let { snapshot ->
                            SceneRecovery.commands(snapshot).forEach { command ->
                                YeelightLan.manualControl(device, command)
                            }
                        }
                    }.exceptionOrNull()
                    withContext(Dispatchers.Main.immediate) {
                        stopped++
                        status = "Stoppe Lichtshow: " + stopped + "/" + chosen.size +
                            " Lampen geprüft …"
                    }
                    device.ip to issue
                }
            } }.awaitAll()
        }
        val failures = outcomes.mapNotNull { (ip, problem) ->
            problem?.let { ip to (it.message ?: it.javaClass.simpleName) }
        }.toMap()
        val failed = failures.keys
        val beforeCount = recoveryJournal.size
        setRunningIps(failed)
        setRecoveryJournal(recoveryJournal.filterKeys { it in failed })
        lastRecoveryFailures = failures
        if (failed.isEmpty()) {
            activeScene = null
            musicDeviceSession = false
            prefs.edit().putBoolean("music-light-session", false).commit()
            musicLightArmed = false
            musicLightStatus = "Musik-Lichtsteuerung nicht aktiviert"
        }
        val success = chosen.size - failed.size
        val result = "Gestoppt" + (if (beforeCount > 0)
            " und ursprünglicher Zustand wiederhergestellt" else "") +
            ": $success/" + chosen.size + " Lampen"
        // Do not report a total failure for ONE offline bulb or destroy its
        // persisted snapshot. Other lamps remain available for future shows.
        return if (failures.isEmpty()) result
        else result + " · " + failures.size +
            " ungeklärt, z. B. Lampe " + failures.keys.first().substringAfterLast('.') +
            " – nur diese Lampen bleiben geschützt"
    }

    /**
     * A failed/offline/unsupported bulb must not prevent other lamps from
     * starting. Only bulbs with safely captured original state are modified
     * when recovery is enabled. Keep every attempted IP in the stop journal
     * even when the acknowledgement is ambiguous.
     */
    private fun startFlow(
        id: String,
        name: String,
        chosen: List<YeelightDevice>,
        expression: (index: Int, count: Int) -> String,
    ) {
        if (busy) {
            status = "Bitte den laufenden Vorgang abschließen"
            return
        }
        if (chosen.isEmpty()) {
            status = "Keine Lampen im Zielbereich. Unter Ziel die Auswahl prüfen."
            return
        }
        if (chosen.size > 24) {
            status = "Maximal 24 Lampen pro Lichtlauf; bitte eine kleinere Auswahl wählen."
            return
        }
        task("Prüfe " + chosen.size + " Lampen für " + name + " …") {
            // Preserve a previous unconfirmed stop journal. Never discard it
            // on a second scene start or race a stop with new light commands.
            if (musicDeviceSession || musicStopPending) {
                return@task "Musiklicht zuerst stoppen und wiederherstellen"
            }
            if (recoveryPending) haltRunning(false)
            val targets = SceneTargetPolicy.flowTargets(chosen)
            val pending = LampOperationGate.pendingIps(runningDeviceIps, recoveryJournal.keys)
            val incompatible = targets.unsupported
            val eligible = targets.supported.filterNot { it.ip in pending }
            val stillPending = targets.supported.filter { it.ip in pending }
            val uniqueCount = targets.supported.size + incompatible.size
            val skipped = incompatible.map {
                SceneLampResult(displayName(it), "Übersprungen",
                    "start_cf wird vom Gerät nicht unterstützt")
            } + stillPending.map {
                SceneLampResult(displayName(it), "Geschützt",
                    "Vorheriger Stopp/Wiederherstellung nicht bestätigt; nur diese Lampe ausgenommen")
            }
            val failedPreflight = mutableListOf<SceneLampResult>()
            val safe = if (restoreAfterStop) {
                val gate = Semaphore(3)
                val attempts = withContext(Dispatchers.IO) {
                    eligible.map { device -> async {
                        gate.withPermit {
                            device to runCatching {
                                val snap = YeelightLan.readStatus(device)
                                require(SceneRecovery.supported(snap)) {
                                    "Ausgangszustand nicht vollständig lesbar"
                                }
                                if (device.methods.isNotEmpty()) {
                                    val required = SceneRecovery.commands(snap).map { it.method }
                                    require(device.methods.containsAll(required)) {
                                        "Wiederherstellung wird nicht unterstützt"
                                    }
                                }
                                snap
                            }
                        }
                    } }.awaitAll()
                }
                attempts.mapNotNull { (device, result) ->
                    result.fold(
                        onSuccess = { device to it },
                        onFailure = {
                            failedPreflight += SceneLampResult(displayName(device),
                                "Übersprungen", it.message ?: "Status nicht lesbar")
                            null
                        }
                    )
                }
            } else eligible.map { it to null }
            val runnable = safe.map { it.first }
            lastSceneReport = skipped + failedPreflight
            if (runnable.isEmpty()) {
                return@task "0/" + uniqueCount +
                    " Lampen startbereit. Gerätebericht unter Szenen prüfen."
            }
            // Durable safety state is committed before the first light command.
            setRecoveryJournal(recoveryJournal + safe.mapNotNull { (device, snap) ->
                snap?.let { device.ip to it }
            }.toMap())
            setRunningIps(runningDeviceIps + runnable.map { it.ip }.toSet())

            var completed = 0
            val gate = Semaphore(3)
            val results = withContext(Dispatchers.IO) {
                runnable.mapIndexed { index, device -> async {
                    gate.withPermit {
                        val result = runCatching {
                            YeelightLan.play(device, expression(index, runnable.size))
                        }
                        withContext(Dispatchers.Main.immediate) {
                            completed++
                            status = "Lichtlauf startet: " + completed + "/" + runnable.size +
                                " Lampen geprüft …"
                        }
                        device to result
                    }
                } }.awaitAll()
            }
            val verified = results.count {
                it.second.getOrNull() == FlowStartState.VERIFIED
            }
            val unverified = results.count {
                it.second.getOrNull() == FlowStartState.UNVERIFIED
            }
            val failed = results.filter { it.second.isFailure }.map { (device, result) ->
                SceneLampResult(displayName(device), "Fehler",
                    result.exceptionOrNull()?.message ?: "Start nicht bestätigt")
            }
            lastSceneReport = skipped + failedPreflight +
                results.mapNotNull { (device, result) ->
                    when (result.getOrNull()) {
                        FlowStartState.VERIFIED ->
                            SceneLampResult(displayName(device), "Läuft", "flowing=1 bestätigt")
                        FlowStartState.UNVERIFIED ->
                            SceneLampResult(displayName(device), "Unbestätigt",
                                "Startbefehl mit OK akzeptiert, flowing nicht eindeutig lesbar")
                        null -> null
                    }
                } + failed
            activeScene = if (verified + unverified > 0) id else null
            val missed = uniqueCount - verified - unverified
            name + ": " + verified + "/" + uniqueCount + " Lampen bestätigt" +
                (if (unverified > 0) " · " + unverified + " Start(s) noch unbestätigt" else "") +
                (if (missed > 0) " · " + missed + " nicht gestartet – Bericht ansehen" else "") +
                (if (restoreAfterStop) " · vorheriger Lichtzustand gespeichert" else "")
        }
    }

    fun play(scene: LoftScene) =
        startFlow(scene.id, scene.name, selected()) { index, _ -> scene.expression(index) }

    fun playChase(config: ChaseConfig, choice: String, name: String = "Lichtlauf") {
        startFlow("chase", name, chooseDevices(choice)) { index, count ->
            ChaseCompiler.expression(config, index, count)
        }
    }

    fun stop() {
        // Live microphone lighting can still have in-flight RGB writes.
        // Disarm and wait for them before restoring original lamp states.
        if (musicLightArmed || musicDeviceSession) {
            disarmMusicLights()
            return
        }
        task("Stoppe Lichtshow und stelle bei Bedarf den vorherigen Zustand wieder her …") {
            haltRunning(true)
        }
    }

    /** Only run after Android runtime permission is granted by the Activity. */
    fun startMicrophone() {
        if (InternalAudioBridge.running) {
            status = "Interne Audioanalyse zuerst stoppen, bevor das Mikrofon gestartet wird."
            return
        }
        if (microphoneRunning) return
        microphone = MicrophoneCapture()
        val capture = microphone ?: return
        microphoneRunning = true
        musicFrame = null
        musicBeatCount = 0L
        musicLightStatus = if (musicLightArmed) "Musik-Lichtsteuerung aktiv"
            else "Mikrofon läuft nur im Vordergrund · kein Audio wird gespeichert"
        scope.launch {
            try {
                capture.run { frame ->
                    withContext(Dispatchers.Main.immediate) {
                        if (microphoneRunning) consumeMusicFrame(frame)
                    }
                }
            } catch (e: Exception) {
                if (microphoneRunning) {
                    status = "Mikrofon-Fehler: " + (e.message ?: e.javaClass.simpleName)
                }
            } finally {
                microphoneRunning = false
                microphone = null
                musicFrame = null
                if (musicLightArmed) disarmMusicLights()
            }
        }
    }

    private fun consumeMusicFrame(frame: MusicFrame) {
        musicFrame = frame
        if (frame.beat) {
            musicBeatCount++
            onMusicBeat(frame)
        }
    }

    fun startInternalAudio(resultCode: Int, grant: android.content.Intent) {
        if (microphoneRunning || InternalAudioBridge.running) {
            status = "Zuerst die bisherige Audioquelle stoppen."
            return
        }
        try {
            musicFrame = null
            musicBeatCount = 0L
            InternalAudioCaptureService.start(app, resultCode, grant)
            status = "Android-Freigabe erteilt · interne Audioquelle wird gestartet"
        } catch (e: Exception) {
            status = "Interne Audioanalyse konnte nicht starten: " + (e.message ?: "Fehler")
        }
    }

    fun stopInternalAudio() {
        InternalAudioCaptureService.stop(app)
    }

    fun stopMicrophone() {
        microphoneRunning = false
        microphone?.stop()
        musicFrame = null
        if (musicLightArmed && !InternalAudioBridge.running) disarmMusicLights()
    }

    /**
     * A second explicit user action arms actual bulbs. Capture every original
     * state and commit recovery data BEFORE sending a single lighting command.
     * Only explicitly selected user targets may control music lighting.
     */
    fun armMusicLights() {
        if (!(microphoneRunning || InternalAudioBridge.running) || musicLightArmed) return
        val chosen = selected()
        if (chosen.isEmpty()) {
            status = "Erst Lampen suchen und den Musik-Zielbereich wählen"
            return
        }
        task("Lese vorherige Lampenzustände für musikreaktives Licht …") {
            if (recoveryPending) haltRunning(false)
            val pending = LampOperationGate.pendingIps(runningDeviceIps, recoveryJournal.keys)
            check(chosen.none { it.ip in pending }) {
                "Musiklicht: mindestens eine gewählte Lampe hat eine offene Wiederherstellung. " +
                    "Diese Lampe zuerst ausschließen."
            }
            val snapshots = withContext(Dispatchers.IO) {
                chosen.map { device -> async {
                    val snapshot = YeelightLan.readStatus(device)
                    require(SceneRecovery.supported(snapshot)) {
                        "Musiklicht abgebrochen: Farbzustand von " + displayName(device) + " nicht vollständig lesbar"
                    }
                    if (device.methods.isNotEmpty()) {
                        val required = SceneRecovery.commands(snapshot).map { it.method }.toSet() +
                            setOf("set_power", "set_rgb")
                        require(device.methods.containsAll(required)) {
                            "Musiklicht nicht von " + displayName(device) + " unterstützt"
                        }
                    }
                    device.ip to snapshot
                } }.awaitAll().toMap()
            }
            check(microphoneRunning || InternalAudioBridge.running) {
                "Audioquelle wurde vor der Lichtaktivierung gestoppt"
            }
            setRecoveryJournal(snapshots)
            setRunningIps(chosen.map { it.ip }.toSet())
            check(prefs.edit().putBoolean("music-light-session", true).commit()) {
                "Musikmodus konnte nicht sicher gesichert werden"
            }
            musicDeviceSession = true
            activeScene = "music-live"
            musicLightArmed = true
            musicColorBeat = 0L
            lastMusicCommandAt = Long.MIN_VALUE / 2
            val failed = withContext(Dispatchers.IO) {
                chosen.map { device -> async {
                    runCatching { YeelightLan.manualControl(device, LampCommands.power(true)) }
                        .exceptionOrNull()?.let { device.ip }
                } }.awaitAll().filterNotNull()
            }
            musicLightStatus = chosen.size.toString() +
                " Lampen gewählt · max. 1 Farbwechsel je 1,5 s / Lampe"
            if (!microphoneRunning && !InternalAudioBridge.running) {
                val stopResult = haltRunning(false)
                "Audioanalyse beendet; Beleuchtung wiederhergestellt · " + stopResult
            } else if (failed.isEmpty()) "Musiklicht aktiviert · vorheriger Zustand gesichert"
            else "Musiklicht aktiviert · " + failed.size + " Lampen nicht einschaltbar; Stoppen zum Zurücksetzen"
        }
    }

    private fun onMusicBeat(frame: MusicFrame) {
        if (!musicLightArmed || busy || musicSending || runningDeviceIps.isEmpty()) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (!MusicLightPolicy.maySend(frame, lastMusicCommandAt, now)) return
        val chosen = orderedDevices().filter { it.ip in runningDeviceIps }
        if (chosen.isEmpty()) return
        musicSending = true
        lastMusicCommandAt = now
        musicColorBeat++
        val number = musicColorBeat
        scope.launch {
            try {
                val problems = withContext(Dispatchers.IO) {
                    chosen.mapIndexed { index, device -> async {
                        runCatching {
                            YeelightLan.manualControl(
                                device,
                                LampCommand("set_rgb", listOf(
                                    MusicLightPolicy.color(frame, index, number), "smooth", 350
                                ))
                            )
                        }.exceptionOrNull()?.let { device.ip }
                    } }.awaitAll().filterNotNull()
                }
                musicLightStatus = if (problems.isEmpty()) {
                    chosen.size.toString() + " Lampen · echter Bassimpuls · gedrosselte RGB-Farbwechsel"
                } else {
                    (chosen.size - problems.size).toString() + "/" + chosen.size +
                        " Lampen reagieren · " + problems.size + " Fehler"
                }
            } finally {
                musicSending = false
            }
        }
    }

    fun disarmMusicLights() {
        if ((!musicLightArmed && !musicDeviceSession) || musicStopPending) return
        // Stop new beat commands immediately, then await any already in flight
        // before restoring. Otherwise a late RGB write could undo recovery.
        musicLightArmed = false
        musicStopPending = true
        scope.launch {
            while (busy || musicSending) delay(50)
            task("Stoppe Musiklicht und stelle vorherige Beleuchtung wieder her …") {
                try { haltRunning(false) }
                finally { musicStopPending = false }
            }
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
    fun close() {
        InternalAudioBridge.detach(internalAudioSink)
        microphone?.stop()
        if (musicDeviceSession) {
            // Activity may be destroyed immediately after ON_STOP.
            // Allow a short bounded network cleanup to finish; a power loss
            // still leaves the durable journal for explicit retry next launch.
            if (!musicStopPending) disarmMusicLights()
            scope.launch {
                withTimeoutOrNull(12_000L) {
                    while (musicStopPending) delay(50)
                }
                scope.cancel()
            }
        } else {
            scope.cancel()
        }
    }
}
