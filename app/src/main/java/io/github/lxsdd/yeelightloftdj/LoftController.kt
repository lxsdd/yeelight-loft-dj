package io.github.lxsdd.yeelightloftdj

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
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
    init { YeelightLan.installQuotaState(app) }
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
    private var musicAcceptanceEpoch = 0L


    var devices by mutableStateOf(loadKnown())
        private set
    var assignments by mutableStateOf(devices.associate { it.id to (prefs.getString("room." + it.id, "Nicht zugeordnet") ?: "Nicht zugeordnet") })
        private set
    var roomNames by mutableStateOf(loadRooms())
        private set
    var customSelectedIds by mutableStateOf(loadSelection())
        private set
    var namedGroups by mutableStateOf(loadNamedGroups())
        private set
    var lastSceneReport by mutableStateOf<List<SceneLampResult>>(emptyList())
        private set
    // Ephemeral, local-only diagnostics. No stored IPs, packets or private topology.
    var lastPowerResults by mutableStateOf<Map<String, PowerProbe>>(emptyMap())
        private set
    var lastPowerDesiredOn by mutableStateOf<Boolean?>(null)
        private set
    var lastPowerSummary by mutableStateOf("")
        private set
    var lastRecoveryFailures by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    var recoveryReadReports by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    private val lastRecoveryReadAt = mutableMapOf<String, Long>()
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
    var scannerRunning by mutableStateOf(false)
        private set
    var scannerActiveIndex by mutableStateOf<Int?>(null)
        private set
    var scannerSteps by mutableStateOf(0L)
        private set
    @Volatile private var scannerStopRequested = false
    private var scannerJob: Job? = null
    var lastSeenIps by mutableStateOf<Set<String>>(emptySet())
        private set
    var lastMulticastIps by mutableStateOf<Set<String>>(emptySet())
        private set
    var lastDirectIps by mutableStateOf<Set<String>>(emptySet())
        private set
    var lastDirectedUdpIps by mutableStateOf<Set<String>>(emptySet())
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
    private var scannerOffIps = readScannerOffIps()
    // A failed set_music(0) must survive Activity/process death. Never silently
    // replay a rejected firmware command when "Stop" is pressed again.
    private var unconfirmedMusicStopIps =
        prefs.getStringSet("music-stop-unconfirmed-ips", emptySet()).orEmpty().toSet()
    // Session-local cooldown: a quota rejection cannot cause a retry storm.
    private val lastQuotaRejectionAt = mutableMapOf<String, Long>()

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

    private fun loadNamedGroups(): Map<String, Set<String>> = runCatching {
        val saved = JSONArray(prefs.getString("named-lamp-groups", "[]") ?: "[]")
        (0 until saved.length()).mapNotNull { index ->
            val entry = saved.optJSONObject(index) ?: return@mapNotNull null
            val name = entry.optString("name").trim()
            val ids = entry.optJSONArray("ids") ?: return@mapNotNull null
            if (name.isBlank()) null else name to
                (0 until ids.length()).mapNotNull { j ->
                    ids.optString(j).takeIf { it.isNotBlank() }
                }.toSet()
        }.toMap()
    }.getOrDefault(emptyMap())

    private fun writeNamedGroups(revised: Map<String, Set<String>>) {
        val arr = JSONArray()
        revised.forEach { (name, ids) ->
            arr.put(JSONObject().put("name", name)
                .put("ids", JSONArray(ids.toList())))
        }
        check(prefs.edit().putString("named-lamp-groups", arr.toString()).commit()) {
            "Gruppen konnten nicht gespeichert werden"
        }
        namedGroups = revised
    }

    fun addGroup(name: String) {
        val cleaned = name.trim()
        if (!canOrganizeLamps || !NamedLampGroups.validName(cleaned) ||
            namedGroups.keys.any { it.equals(cleaned, ignoreCase = true) } ||
            namedGroups.size >= 24) {
            status = "Ungültiger oder bereits verwendeter Gruppenname"
            return
        }
        writeNamedGroups(namedGroups + (cleaned to emptySet()))
        chooseTarget("Gruppe:" + cleaned)
        status = "Gruppe angelegt: " + cleaned + " · bitte Lampen auswählen"
    }

    fun renameGroup(name: String, next: String) {
        val clean = next.trim()
        if (!canOrganizeLamps || name !in namedGroups || !NamedLampGroups.validName(clean) ||
            namedGroups.keys.any { it != name && it.equals(clean, true) }) return
        val revised = namedGroups.mapKeys { (key, _) -> if (key == name) clean else key }
        writeNamedGroups(revised)
        if (target == "Gruppe:" + name) chooseTarget("Gruppe:" + clean)
        status = "Gruppe umbenannt: " + clean
    }

    fun deleteGroup(name: String) {
        if (!canOrganizeLamps || name !in namedGroups) return
        val wasTarget = target == "Gruppe:" + name
        writeNamedGroups(namedGroups - name)
        if (wasTarget) chooseTarget("Auswahl")
        status = "Gruppe entfernt · Lampen bleiben gespeichert"
    }

    fun setGroupMember(name: String, deviceId: String, selected: Boolean) {
        if (!canOrganizeLamps || name !in namedGroups ||
            devices.none { it.id == deviceId }) return
        val current = namedGroups[name].orEmpty()
        val next = if (selected) current + deviceId else current - deviceId
        writeNamedGroups(namedGroups + (name to next))
    }

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
        namedGroups.keys.map { "Gruppe:" + it } +
        roomNames.filter { it != "Nicht zugeordnet" }.map { "Raum:$it" } +
        orderedDevices().map { "Lampe:" + it.id }

    fun targetDescription(choice: String): String = when {
        choice == "Alle" ->
            "Alle gespeicherten Lampen, auch offline. Beim Start werden Fehler je Lampe angezeigt."
        choice == "Auswahl" -> "Deine " + customSelectedIds.size +
            " ausgewählten Lampen. Offline-Lampen können separat fehlschlagen."
        choice.startsWith("Gruppe:") -> namedGroups[choice.removePrefix("Gruppe:")]
            ?.let { "Bearbeitbare Gruppe mit " + it.size + " Lampen." } ?: ""
        else -> ""
    }

    fun targetLabel(value: String): String = when {
        value.startsWith("Raum:") -> value.removePrefix("Raum:")
        value.startsWith("Gruppe:") -> value.removePrefix("Gruppe:")
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

    val quotaRecoveryHint: Boolean
        get() = lastRecoveryFailures.values.any {
            QuotaRecoveryPolicy.isQuotaRejection(it) ||
                it.contains("Yeelight-LAN-Limit", ignoreCase = true)
        }

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
        !YeelightLan.isLanQuarantined(device) &&
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

    /**
     * Read the real firmware state for exactly ONE pending IP. The requested
     * check never sends a lighting command. Only a proven, already safe state
     * removes the three local tracking markers atomically.
     *
     * If the original snapshot differs, present a normal restore action;
     * do NOT silently overwrite a lamp following a read-only check.
     */
    fun checkRecoveryLamp(ip: String) {
        val pending = LampOperationGate.pendingIps(runningDeviceIps, recoveryJournal.keys)
        if (busy || scannerRunning || musicLightArmed || musicStopPending || musicSending ||
            !YeelightLan.isLocalIp(ip) || ip !in pending
        ) {
            status = "Prüfung nicht möglich: Lampe nicht offen oder ein Lichtvorgang aktiv"
            return
        }
        // Music-session persistence alone is not evidence of a live stream.
        // Permit ONE user-requested read, but never clear a marker when the
        // firmware's music-mode stop was not confirmed.
        val targetDevice = devices.firstOrNull { it.ip == ip } ?: YeelightLan.manual(ip)
        if (YeelightLan.musicModeActive(targetDevice)) {
            status = "Aktiver Musik-Rückkanal: keine parallele Statusabfrage"
            return
        }
        val now = SystemClock.elapsedRealtime()
        val lastRead = lastRecoveryReadAt[ip]
        if (lastRead != null && now - lastRead in 0 until 10_000L) {
            status = "Zustandsabfrage für diese Lampe erst nach 10 Sekunden erneut möglich"
            return
        }
        lastRecoveryReadAt[ip] = now
        task("Prüfe die Lampe ." + ip.substringAfterLast('.') + " lesend …") {
            val device = targetDevice
            val scannerMode = ip in scannerOffIps
            val snapshot = recoveryJournal[ip]
            val result = withContext(Dispatchers.IO) {
                runCatching { YeelightLan.readRecoveryObservation(device) }
            }
            if (result.isFailure) {
                val reason = result.exceptionOrNull()?.message
                    ?: "TCP-Zustandsprüfung ohne Antwort"
                recoveryReadReports = recoveryReadReports + (ip to
                    "Nicht bestätigt: $reason")
                return@task "Zustandsprüfung nicht möglich; Lampe bleibt geschützt: $reason"
            }
            val read = result.getOrThrow()
            val decision = RecoveryReadPolicy.decide(
                read.flowing, read.snapshot, snapshot, scannerMode,
                reportedPowerOn = read.powerOn
            )
            val label = displayName(device)
            if (RecoveryReadPolicy.canClear(decision, musicSessionUnconfirmed = musicDeviceSession)) {
                val nextRunning = runningDeviceIps - ip
                val nextJournal = recoveryJournal - ip
                val nextScanner = scannerOffIps - ip
                check(prefs.edit()
                    .putString("running-flow-ips", JSONArray(nextRunning.toList()).toString())
                    .putString("pending-scene-recovery", SceneRecovery.encode(nextJournal))
                    .putString("scanner-off-ips", JSONArray(nextScanner.toList()).toString())
                    .commit()) { "Bestätigte Wiederherstellung konnte nicht gespeichert werden" }
                runningDeviceIps = nextRunning
                recoveryJournal = nextJournal
                scannerOffIps = nextScanner
                lastRecoveryFailures = lastRecoveryFailures - ip
                lastQuotaRejectionAt.remove(ip)
                if (nextRunning.isEmpty() && nextJournal.isEmpty() && !musicDeviceSession) {
                    activeScene = null
                }
                val success = when(decision) {
                    RecoveryReadDecision.ALREADY_RESTORED ->
                        "Farbfluss=0 und ursprünglicher Lichtzustand stimmen überein"
                    RecoveryReadDecision.SCANNER_OFF ->
                        "Scanner-Lampe meldet power=off"
                    else -> "Farbfluss=0; kein Originalzustand zum Wiederherstellen gespeichert"
                }
                recoveryReadReports = recoveryReadReports + (ip to "Bestätigt · " + success)
                "$label: bereits sicher · lokale Vormerkung automatisch entfernt"
            } else {
                val reason = when(decision) {
                    RecoveryReadDecision.ALREADY_RESTORED,
                    RecoveryReadDecision.FLOW_STOPPED_NO_SNAPSHOT,
                    RecoveryReadDecision.SCANNER_OFF ->
                        "Lichtzustand lesend geprüft; Musikmodus-Stopp wurde noch nicht " +
                            "bestätigt. Schutz bleibt bestehen. Keine automatischen Befehle."
                    RecoveryReadDecision.FLOW_STILL_RUNNING ->
                        "Farbfluss läuft noch. Bitte normal stoppen."
                    RecoveryReadDecision.RESTORE_REQUIRED ->
                        "Farbfluss ist aus, früherer Lichtzustand weicht jedoch ab. " +
                            "Bitte normal wiederherstellen."
                    else -> "Gerätestatus nicht eindeutig lesbar. " +
                        "Die Lampe bleibt geschützt; kein automatisches Entsperren."
                }
                recoveryReadReports = recoveryReadReports + (ip to reason)
                "$label: $reason"
            }
        }
    }

    /** Explicitly discard only one stale app-side recovery marker.
     * No power-cycle claim, no LAN command, NO hardware-state verification.
     * Use only if the user accepts a possibly still-running bulb flow and
     * agrees that its original static state won't be restored.
     * Both local journal keys commit atomically before state changes.
     */
    fun discardRecoveryForLamp(
        ip: String,
        acceptsPossibleRunningFlow: Boolean,
        acceptsLostRestore: Boolean,
    ): Boolean {
        val pending = LampOperationGate.pendingIps(runningDeviceIps, recoveryJournal.keys)
        val liveReverseChannel = pending.any { pendingIp ->
            val lamp = devices.firstOrNull { it.ip == pendingIp }
            lamp != null && YeelightLan.musicModeActive(lamp)
        }
        if (!RecoveryAbandonPolicy.canDiscard(
                ip, pending, acceptsPossibleRunningFlow, acceptsLostRestore,
                busy || musicLightArmed || musicStopPending || musicSending || liveReverseChannel
            )) {
            status = "Freigabe abgelehnt: Beide Folgen des Verwerfens müssen " +
                "ausdrücklich bestätigt werden."
            return false
        }
        val nextRunning = runningDeviceIps - ip
        val nextJournal = recoveryJournal - ip
        val remaining = LampOperationGate.pendingIps(nextRunning, nextJournal.keys)
        val keepMusicSession = musicDeviceSession && remaining.isNotEmpty()
        val nextUnconfirmedStops = unconfirmedMusicStopIps - ip
        val saved = prefs.edit()
            .putString("running-flow-ips", JSONArray(nextRunning.toList()).toString())
            .putString("pending-scene-recovery", SceneRecovery.encode(nextJournal))
            .putString("scanner-off-ips", JSONArray((scannerOffIps - ip).toList()).toString())
            .putStringSet("music-stop-unconfirmed-ips", nextUnconfirmedStops)
            .putBoolean("music-light-session", keepMusicSession)
            .commit()
        if (!saved) {
            status = "Wiederherstellungsfreigabe konnte nicht gespeichert werden"
            return false
        }
        runningDeviceIps = nextRunning
        recoveryJournal = nextJournal
        scannerOffIps = scannerOffIps - ip
        unconfirmedMusicStopIps = nextUnconfirmedStops
        if (!keepMusicSession) {
            musicDeviceSession = false
            musicLightArmed = false
            musicLightStatus = "Musikmodus-Status der verworfenen Lampe NICHT bestätigt"
        }
        lastRecoveryFailures = lastRecoveryFailures - ip
        lastQuotaRejectionAt.remove(ip)
        if (nextRunning.isEmpty() && nextJournal.isEmpty()) activeScene = null
        status = "Lokale Wiederherstellung für Lampe ." +
            ip.substringAfterLast('.') + " bewusst verworfen. " +
            "Ein eventuell noch laufender Farbfluss/Musikmodus wurde NICHT gestoppt; " +
            "frühere Farbe/Helligkeit wurde NICHT wiederhergestellt."
        return true
    }

    fun pendingRecoveryIps(): List<String> =
        LampOperationGate.pendingIps(runningDeviceIps, recoveryJournal.keys).sorted()

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

    private fun readScannerOffIps(): Set<String> = runCatching {
        val json = JSONArray(prefs.getString("scanner-off-ips", "[]") ?: "[]")
        (0 until json.length()).map { json.getString(it) }
            .filter(YeelightLan::isLocalIp).toSet()
    }.getOrDefault(emptySet())

    /** Write all scanner/off markers together, before touching a bulb. */
    private fun setScannerTracking(scannerIps: Set<String>, trackedIps: Set<String>) {
        check(prefs.edit()
            .putString("scanner-off-ips", JSONArray(scannerIps.toList()).toString())
            .putString("running-flow-ips", JSONArray(trackedIps.toList()).toString())
            .commit()) { "Scanner-Sicherheitsstatus konnte nicht gespeichert werden" }
        scannerOffIps = scannerIps
        runningDeviceIps = trackedIps
    }

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
                val revisedGroups = NamedLampGroups.migrateId(namedGroups,
                    previous.id, replacement.id)
                if (revisedGroups != namedGroups) writeNamedGroups(revisedGroups)
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

    fun discover() = task("Suche Yeelights schonend über WLAN (UDP) …") {
        // Normal discovery must NEVER open 254 TCP sockets or send get_prop.
        // The previous "Alle Lampen suchen" silently did exactly that.
        // Deep TCP diagnostics remain a separately chosen, advanced action.
        val previous = devices.toList()
        val blockedIps = previous.filter(YeelightLan::isLanQuarantined)
            .map { it.ip }.toSet()
        val (multicast, directed) = withContext(Dispatchers.IO) {
            val discovery = DiscoveryTrafficPolicy.normalSearch(
                known = previous,
                quarantinedIps = blockedIps,
                multicast = { YeelightLan.discover(app) },
                directed = { targets ->
                    runCatching { YeelightLan.directedUdpDiscover(app, targets) }
                        .getOrDefault(emptyList())
                },
            )
            discovery.multicast to discovery.directed
        }
        directedUdpDiagnostics = ""
        lastMulticastIps = multicast.map { it.ip }.toSet()
        lastDirectIps = emptySet() // no TCP probing occurred in this search
        lastDirectedUdpIps = directed.map { it.ip }.toSet()
        searchDiagnostics = "Schonende UDP-Gerätesuche: " + lastMulticastIps.size +
            " Multicast-Treffer · " + lastDirectedUdpIps.size +
            " gezielte Antworten bekannter Lampen" +
            "\nTCP-LAN-Steuerverbindungen/Get-Prop-Abfragen: 0 (absichtlich)" +
            "\nUDP-Antwortpakete: " + YeelightLan.lastSearchPackets +
            " · davon nicht auswertbar: " + YeelightLan.lastSearchInvalidPackets +
            "\nWLAN-Sendeadresse: " + YeelightLan.lastSearchSourceAddress +
            " · Wi-Fi-Netzbindung: " + YeelightLan.lastSearchBoundToWifiNetwork +
            " · Treffer je Suchrunde: " + YeelightLan.lastSearchRoundUnique.joinToString("/") +
            " · Absender-/Location-Abweichungen: " + YeelightLan.lastSearchSourceMismatches
        val merged = YeelightLan.mergeDevices(multicast, directed)
        mergeFound(merged, "Multicast + gezieltes UDP (ohne TCP)")
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
                "\nTCP wird bei der 12-Runden-Probe NICHT neu getestet." +
                " Letzte kombinierte Suche: " + lastDirectIps.size +
                " TCP-Treffer, " + both + " davon auch per Multicast." +
                " Nur TCP: " + directOnly.size +
                (if (lastDirectIps.isEmpty()) "\nFür einen aktuellen TCP-Vergleich zuerst „Alle Lampen suchen“ ausführen." else "") +
                "\nPro Runde UDP-Antworter: " +
                YeelightLan.lastSearchRoundUnique.joinToString("/") +
                "\nAntwortpakete insgesamt: " + YeelightLan.lastSearchPackets +
                " · nicht lesbar: " + YeelightLan.lastSearchInvalidPackets +
                "\nAbsender-IP: " + YeelightLan.lastSearchSourceAddress +
                " · Wi-Fi-Netzbindung: " + YeelightLan.lastSearchBoundToWifiNetwork +
                " · Absender/Location-Abweichungen: " +
                YeelightLan.lastSearchSourceMismatches
            mergeFound(replies, "12-Runden-Multicast-Gegenprobe")
            // A UDP-only experiment must not make previously TCP-confirmed
            // bulbs appear offline in the device list.
            lastSeenIps = lastSeenIps + lastDirectIps
            searchDiagnostics
        }

    fun discoveryDetail(device: YeelightDevice): String {
        val rounds = YeelightLan.lastSearchRoundResponders
        val responseRounds = rounds.count { device.ip in it }
        val packets = YeelightLan.lastSearchRepliesPerIp[device.ip] ?: 0
        val tcp = if (device.ip in lastDirectIps) "TCP bestätigt" else "TCP zuletzt nicht bestätigt"
        val udp = if (device.ip in lastMulticastIps) "Multicast geantwortet"
            else "Multicast keine Antwort"
        val directed = if (device.ip in lastDirectedUdpIps) " · Direkt-UDP bestätigt"
            else ""
        return tcp + " · " + udp + directed + " · " + responseRounds +
            "/" + rounds.size + " Multicast-Runden · " +
            packets + " Multicast-Antwortpakete"
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
            val replied = withContext(Dispatchers.IO) {
                YeelightLan.directedUdpDiscover(app, missing)
            }
            val replyIps = replied.map { it.ip }.toSet()
            lastDirectedUdpIps = lastDirectedUdpIps + replyIps
            if (replied.isNotEmpty()) {
                mergeFound(replied, "Direkt-UDP mit Gerätefähigkeiten")
                lastSeenIps = lastSeenIps + lastDirectIps + lastMulticastIps
            }
            directedUdpDiagnostics = "Gezielte UDP-Gegenprobe: " + replied.size + "/" +
                missing.size + " antworten · " +
                if (replied.isEmpty()) "Kein eindeutiger Rückschluss möglich"
                else "Modell und unterstützte Methoden aus Direkt-UDP übernommen. " +
                    "Die Multicast-Nichtantwort bleibt als Netzwerkhinweis sichtbar."
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
        val prunedGroups = NamedLampGroups.removeId(namedGroups, device.id)
        if (prunedGroups != namedGroups) writeNamedGroups(prunedGroups)
        devices = devices.filterNot { it.id == device.id }
        assignments = assignments - device.id
        lastSeenIps = lastSeenIps - device.ip
        lastMulticastIps = lastMulticastIps - device.ip
        lastDirectIps = lastDirectIps - device.ip
        lastDirectedUdpIps = lastDirectedUdpIps - device.ip
        lampSnapshots = lampSnapshots - device.ip
        lampOrder = lampOrder - device.id
        prefs.edit().putString("lamp-order", JSONArray(lampOrder).toString()).apply()
        prefs.edit().remove("room." + device.id).remove("alias." + device.ip).apply()
        saveKnown()
        status = "Gerät aus dieser App entfernt"
    }


    fun isLampLanQuarantined(device: YeelightDevice): Boolean =
        YeelightLan.isLanQuarantined(device)

    /** Explicitly invoked from the device UI, never via automatic retry. */
    fun verifyLampLanRecovery(device: YeelightDevice) {
        if (!YeelightLan.isLanQuarantined(device)) {
            status = "Keine bestätigte Firmware-LAN-Sperre für diese Lampe gespeichert."
            return
        }
        task("Prüfe LAN-Wiederherstellung für " + displayName(device) + " einmalig …") {
            val power = withContext(Dispatchers.IO) {
                YeelightLan.verifyLanRecovery(device)
            }
            displayName(device) + ": Firmware antwortet wieder (power=" + power +
                "). LAN-Quarantäne aufgehoben; Steuerung vorsichtig fortsetzen."
        }
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
            status = if (YeelightLan.isLanQuarantined(device))
                "Firmware-LAN-Limit bestätigt: Wellness/andere betroffene Lampe geschützt. " +
                    "Nur eine ausdrückliche Recovery-Leseprüfung ist zugelassen."
            else "Diese Lampe ist noch in einer Lichtshow/Wiederherstellung. " +
                "Bitte zuerst gezielt stoppen."
            return
        }
        task(description + " …") {
            if (command.method == "set_power") {
                val on = command.params.firstOrNull() == "on"
                val result = withContext(Dispatchers.IO) {
                    YeelightLan.setPowerVerified(device, on)
                }
                lastPowerDesiredOn = on
                lastPowerResults = mapOf(device.ip to result)
                lastPowerSummary = PowerVerificationPolicy.summarize(listOf(result))
                lampSnapshots = lampSnapshots - device.ip
                displayName(device) + ": " + lastPowerSummary +
                    " · TCP " + (result.connectMs?.let { "$it ms" } ?: "nicht verbunden")
            } else {
                // Each slider/color selection already requires one acknowledged
                // write. An automatic second get_prop doubles rate use and can
                // exhaust the firmware quota after just a few adjustments.
                withContext(Dispatchers.IO) { YeelightLan.manualControl(device, command) }
                lampSnapshots = lampSnapshots - device.ip
                displayName(device) + ": " + description +
                    " · Befehl angenommen. Für echten Status „Aktualisieren“ wählen."
            }
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
            // Never block healthy bulbs on a repeated automatic stop of a
            // single unreachable bulb. Recovery is always a separate,
            // explicit user action; affected IPs remain protected.
            val pending = LampOperationGate.pendingIps(runningDeviceIps, recoveryJournal.keys)
            val unique = chosen.distinctBy { it.ip }
            val gate = Semaphore(3)
            val outcomes = withContext(Dispatchers.IO) {
                unique.map { device -> async {
                    val result = if (device.ip in pending ||
                        YeelightLan.isLanQuarantined(device))
                        PowerProbe(PowerOutcome.PROTECTED,
                            message = if (YeelightLan.isLanQuarantined(device))
                                "Firmware-LAN-Sperre – keine Änderung"
                            else "Offene Wiederherstellung – keine Änderung")
                    else gate.withPermit {
                        runCatching {
                            YeelightLan.setPowerVerified(device, on)
                        }.getOrElse { error ->
                            PowerProbe(PowerOutcome.NO_ACK,
                                message = error.message ?: error.javaClass.simpleName)
                        }
                    }
                    device.ip to result
                } }.awaitAll().toMap()
            }
            lastPowerDesiredOn = on
            lastPowerResults = outcomes
            lastPowerSummary = PowerVerificationPolicy.summarize(outcomes.values)
            // A power-only get_prop readback does not supply a full RGB/CT
            // snapshot. Do not silently retain a stale displayed snapshot.
            lampSnapshots = lampSnapshots - unique.map { it.ip }.toSet()
            (if (on) "Ein" else "Aus") + ": " + lastPowerSummary +
                " · Messwerte je Lampe unter Schnellsteuerung"
        }
    }

    private fun selected() = chooseDevices(target)
    private fun chooseDevices(choice: String): List<YeelightDevice> = when {
        choice == "Alle" -> orderedDevices()
        choice == "Auswahl" -> orderedDevices().filter { it.id in customSelectedIds }
        choice.startsWith("Gruppe:") -> orderedDevices().filter {
            it.id in namedGroups[choice.removePrefix("Gruppe:")].orEmpty()
        }
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

    fun previewScannerEligibleCount(choice: String): Int {
        val pending = LampOperationGate.pendingIps(runningDeviceIps, recoveryJournal.keys)
        return chooseDevices(choice).distinctBy { it.ip }.count {
            it.ip !in pending && KnightRiderPlan.scannerCompatible(it)
        }
    }


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
        val lastQuotaSnapshot = lastQuotaRejectionAt.toMap()
        var stopped = 0
        val outcomes = withContext(Dispatchers.IO) {
            val limit = Semaphore(3)
            chosen.map { device -> async {
                limit.withPermit {
                    val waitMs = QuotaRecoveryPolicy.remainingMs(
                        SystemClock.elapsedRealtime(), lastQuotaSnapshot[device.ip]
                    )
                    val issue = if (waitMs > 0) {
                        IllegalStateException("Yeelight-LAN-Limit: erneuter Versuch in " +
                            ((waitMs + 999L) / 1000L) + " Sekunden möglich")
                    } else runCatching {
                        if (!MusicStopSafety.permitsStop(device.ip, unconfirmedMusicStopIps)) {
                            error("Music-Mode-Stopp für diese Lampe wurde zuvor nicht bestätigt; " +
                                "keine erneuten set_music-Befehle. Nur lesend prüfen.")
                        }
                        if (device.ip in scannerOffIps) {
                            check(YeelightLan.setPowerVerified(device, false).outcome ==
                                PowerOutcome.VERIFIED) {
                                "Scanner-Lampe konnte nicht sicher ausgeschaltet werden"
                            }
                        } else if (musicDeviceSession) {
                            // Close the firmware's reverse music TCP stream
                            // BEFORE normal LAN commands and status restoration.
                            YeelightLan.stopMusicMode(device)
                        } else {
                            YeelightLan.stop(device)
                        }
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
        val now = SystemClock.elapsedRealtime()
        outcomes.forEach { (ip, problem) ->
            if (QuotaRecoveryPolicy.isQuotaRejection(problem?.message)) {
                lastQuotaRejectionAt[ip] = now
            } else if (problem == null) {
                lastQuotaRejectionAt.remove(ip)
            }
        }
        val failed = failures.keys
        val newUnconfirmedStops = (
            unconfirmedMusicStopIps +
                failures.filterValues(MusicStopSafety::unconfirmed).keys
            ) - outcomes.filter { it.second == null }.map { it.first }.toSet()
        check(prefs.edit().putStringSet("music-stop-unconfirmed-ips",
            newUnconfirmedStops).commit()) {
            "Musikmodus-Stopp-Schutz konnte nicht gespeichert werden"
        }
        unconfirmedMusicStopIps = newUnconfirmedStops
        val beforeCount = recoveryJournal.size
        setScannerTracking(scannerOffIps.intersect(failed), failed)
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
            // Do not automatically retry unresolved bulbs on every scene
            // start: client quota exceeded must not trigger reconnect storms.
            // The target filter below excludes every unresolved IP and
            // preserves the original recovery journal without overwriting.
            if (musicDeviceSession || musicStopPending) {
                return@task "Musiklicht zuerst stoppen und wiederherstellen"
            }
            val firmwareBlocked = chosen.filter(YeelightLan::isLanQuarantined)
                .map { it.ip }.toSet()
            val targets = SceneTargetPolicy.flowTargets(chosen, firmwareBlocked)
            val pending = LampOperationGate.pendingIps(runningDeviceIps, recoveryJournal.keys)
            val incompatible = targets.unsupported
            val eligible = targets.supported.filterNot { it.ip in pending }
            val stillPending = targets.supported.filter { it.ip in pending }
            val uniqueCount = targets.supported.size + incompatible.size + targets.blocked.size
            val skipped = targets.blocked.map {
                SceneLampResult(displayName(it), "Firmware-LAN-Sperre",
                    "Keine Steuerbefehle; einmalige explizite Recovery-Prüfung nötig")
            } + incompatible.map {
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
        if (config.style == ChaseStyle.KNIGHT_RIDER) {
            playKnightRider(config, choice)
        } else {
            startFlow("chase", name, chooseDevices(choice)) { index, count ->
                ChaseCompiler.expression(config, index, count)
            }
        }
    }

    /** Scanner is a host-driven strict black/off effect, NOT a native RGB
     * colour-flow with brightness=1 masquerading as darkness.
     * It deliberately requires confirmed firmware capabilities.
     */
    fun playKnightRider(config: ChaseConfig, choice: String) {
        if (busy || scannerRunning) {
            status = "Ein anderer Vorgang läuft bereits"
            return
        }
        if (musicDeviceSession || musicLightArmed || musicStopPending) {
            status = "Musiklicht bitte zuerst stoppen und wiederherstellen"
            return
        }
        val chosen = chooseDevices(choice).distinctBy { it.ip }
        val pending = LampOperationGate.pendingIps(runningDeviceIps, recoveryJournal.keys)
        val firmwareBlocked = chosen.filter(YeelightLan::isLanQuarantined)
            .map { it.ip }.toSet()
        val eligible = chosen.filter {
            it.ip !in pending && it.ip !in firmwareBlocked &&
                KnightRiderPlan.scannerCompatible(it)
        }
        lastSceneReport = chosen.filterNot { it in eligible }.map {
            SceneLampResult(displayName(it), "Übersprungen",
                when {
                    it.ip in firmwareBlocked -> "Firmware-LAN-Sperre – keine Befehle"
                    it.ip in pending -> "Offene Wiederherstellung"
                    else -> "Erforderlich: bestätigtes set_scene, set_power, get_prop, set_rgb"
                })
        }
        if (eligible.size !in 2..24) {
            status = "Scanner braucht 2–24 geeignete Lampen; nach einer " +
                "Gerätesuche zunächst bestätigte Funktionen prüfen"
            return
        }
        val previouslyTracked = runningDeviceIps
        val previouslyScanner = scannerOffIps
        try {
            // Commit the expected cleanup before the very first power OFF.
            setScannerTracking(previouslyScanner + eligible.map { it.ip },
                previouslyTracked + eligible.map { it.ip })
        } catch (ex: Exception) {
            status = "Scanner nicht gestartet: " + (ex.message ?: "Speicherfehler")
            return
        }
        busy = true
        scannerRunning = true
        scannerStopRequested = false
        scannerActiveIndex = null
        scannerSteps = 0L
        activeScene = "knight_rider"
        status = "Scanner bereitet " + eligible.size + " Lampen vor: alle zunächst AUS …"
        scannerJob = scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    KnightRiderRunner().execute(
                        eligible, config,
                        isStopping = { scannerStopRequested },
                        powerOff = { device ->
                            YeelightLan.setPowerVerified(device, false).outcome ==
                                PowerOutcome.VERIFIED
                        },
                        lightOn = { device, rgb, intensity ->
                            YeelightLan.send(device, "set_scene",
                                listOf("color", rgb, intensity))
                        },
                        waitStep = { time -> delay(time) },
                        onStep = { index, step ->
                            withContext(Dispatchers.Main.immediate) {
                                scannerActiveIndex = index
                                scannerSteps = step
                                status = "Knight Rider: Lampe " + (index + 1) +
                                    "/" + eligible.size + " · Schritt " + step
                            }
                        }
                    )
                }
                // Never clear unknown/offline bulbs just because a cleanup
                // command was sent. Keep only those uncertain in the journal.
                setScannerTracking(previouslyScanner + result.unresolved,
                    previouslyTracked + result.unresolved)
                lastSceneReport = lastSceneReport +
                    eligible.map { bulb ->
                        val unconfirmed = bulb.ip in result.unresolved
                        SceneLampResult(displayName(bulb),
                            if (unconfirmed) "Stopp unbestätigt" else "AUS bestätigt",
                            if (unconfirmed) "Bitte die Wiederherstellung erneut prüfen"
                            else "get_prop(power)=off bestätigt")
                    }
                status = result.error ?: ("Knight Rider beendet · " +
                    (eligible.size - result.unresolved.size) + "/" +
                    eligible.size + " Lampen als AUS bestätigt")
            } catch (ex: Exception) {
                status = "Scannerfehler: " + (ex.message ?: ex.javaClass.simpleName) +
                    " · unbestätigte Lampen zur Wiederherstellung vorgemerkt"
            } finally {
                scannerRunning = false
                scannerActiveIndex = null
                scannerStopRequested = false
                scannerJob = null
                busy = false
                if (activeScene == "knight_rider") activeScene = null
            }
        }
    }

    fun requestStopScanner() {
        if (scannerRunning) {
            scannerStopRequested = true
            status = "Scanner wird gestoppt · sichere AUS-Bestätigung läuft …"
        }
    }



    fun stop() {
        if (scannerRunning) {
            requestStopScanner()
            return
        }
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
    fun armMusicLights() = armMusicLightsInternal(acceptanceTest = false)

    /** Exactly one explicitly chosen Color4; self-stops after 8 seconds. */
    fun armColor4MusicAcceptance() = armMusicLightsInternal(acceptanceTest = true)

    fun color4AcceptanceTargetName(): String? =
        selected().distinctBy { it.ip }.singleOrNull()?.let(::displayName)

    fun color4MusicAcceptanceRefusal(): String? {
        val requested = selected().distinctBy { it.ip }
        val blocked = requested.filter(YeelightLan::isLanQuarantined)
            .map { it.ip }.toSet()
        return Color4MusicAcceptancePolicy.refusal(
            requested, blocked, recoveryPending
        )
    }

    private fun armMusicLightsInternal(acceptanceTest: Boolean) {
        if (!(microphoneRunning || InternalAudioBridge.running) || musicLightArmed) return
        if (acceptanceTest) {
            val refusal = color4MusicAcceptanceRefusal()
            if (refusal != null) {
                status = refusal
                return
            }
        }
        val requested = selected().distinctBy { it.ip }
        val blocked = requested.filter(YeelightLan::isLanQuarantined)
            .map { it.ip }.toSet()
        val chosen = MusicTransportPolicy.eligible(requested, blocked)
        if (chosen.isEmpty()) {
            status = "Keine geeigneten Lampen für Musiklicht: Color4 braucht " +
                "bestätigten set_music-Rückkanal; gesperrte Lampen bleiben geschützt."
            return
        }
        task("Lese vorherige Lampenzustände für musikreaktives Licht …") {
            if (acceptanceTest) {
                // A safety decision taken before task scheduling must be
                // rechecked here. Never auto-stop another bulb or retry
                // unresolved recovery as a side effect of the test.
                val refusal = Color4MusicAcceptancePolicy.refusal(
                    chosen,
                    chosen.filter(YeelightLan::isLanQuarantined)
                        .map { it.ip }.toSet(),
                    recoveryPending
                )
                if (refusal != null) return@task refusal
            } else if (recoveryPending) {
                haltRunning(false)
            }
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
            if (failed.isNotEmpty()) {
                musicLightArmed = false
                val restored = haltRunning(false)
                return@task "Musiklicht abgebrochen: " + failed.size +
                    " Lampen nicht einschaltbar · " + restored
            }
            // Only lamps whose SSDP SUPPORT list explicitly confirms
            // set_music use the reverse connection; other models retain the
            // deliberately quota-limited normal LAN path.
            val streamFailures = withContext(Dispatchers.IO) {
                chosen.filter(YeelightLan::supportsMusicMode).map { device -> async {
                    runCatching { YeelightLan.startMusicMode(device) }
                        .exceptionOrNull()?.let { device.ip to (it.message ?: "Music-Mode-Fehler") }
                } }.awaitAll().filterNotNull()
            }
            if (streamFailures.isNotEmpty()) {
                musicLightArmed = false
                val restored = haltRunning(false)
                return@task "Music-Mode-Rückkanal konnte nicht aufgebaut werden · " +
                    "keine Farbimpulse gesendet · " + restored
            }
            val streamingCount = chosen.count(YeelightLan::musicModeActive)
            musicLightStatus = "$streamingCount Musik-Rückkanäle · " +
                (chosen.size - streamingCount) + " reguläre LAN-Lampen"
            if (!microphoneRunning && !InternalAudioBridge.running) {
                val stopResult = haltRunning(false)
                "Audioanalyse beendet; Beleuchtung wiederhergestellt · " + stopResult
            } else if (acceptanceTest) {
                // Session-scoped auto-stop. Epoch prevents an old timer from
                // stopping a later session after an early manual disarm.
                val token = ++musicAcceptanceEpoch
                scope.launch {
                    delay(Color4MusicAcceptancePolicy.DURATION_MS)
                    if (musicLightArmed && musicDeviceSession &&
                        !musicStopPending && token == musicAcceptanceEpoch) {
                        disarmMusicLights()
                    }
                }
                "Color4-Einzeltest läuft · automatischer Stopp und Wiederherstellung nach 8 s"
            } else "Musiklicht aktiviert · vorheriger Zustand gesichert"
        }
    }

    private fun onMusicBeat(frame: MusicFrame) {
        if (!musicLightArmed || busy || musicSending || runningDeviceIps.isEmpty()) return
        val now = android.os.SystemClock.elapsedRealtime()
        val chosen = orderedDevices().filter { it.ip in runningDeviceIps }
        if (chosen.isEmpty()) return
        val allStreaming = chosen.all(YeelightLan::musicModeActive)
        val interval = if (allStreaming) 350L else MusicLightPolicy.minCommandIntervalMs
        if (!MusicLightPolicy.maySend(frame, lastMusicCommandAt, now, interval)) return
        musicSending = true
        lastMusicCommandAt = now
        musicColorBeat++
        val number = musicColorBeat
        scope.launch {
            try {
                val (attempted, paused, problems) = withContext(Dispatchers.IO) {
                    // One busy or recently queried lamp must not deprive the
                    // others of their own safe beat budget. Preserve the
                    // ORIGINAL spatial index when skipping a quota-low lamp.
                    val permitted = chosen.withIndex().filter {
                        if (YeelightLan.supportsMusicMode(it.value)) {
                            YeelightLan.musicModeActive(it.value)
                        } else {
                            YeelightLan.canSendMusicBeat(it.value)
                        }
                    }
                    val failures = permitted.map { (index, device) -> async {
                        runCatching {
                            val color = MusicLightPolicy.color(frame, index, number)
                            if (YeelightLan.supportsMusicMode(device)) {
                                // Music mode has no ACK and no property push.
                                // Never misreport a write as verified light output.
                                check(YeelightLan.sendMusicRgb(device, color))
                            } else {
                                YeelightLan.manualControl(
                                    device, LampCommand("set_rgb", listOf(color, "smooth", 350))
                                )
                            }
                        }.exceptionOrNull()?.let { device.ip }
                    } }.awaitAll().filterNotNull()
                    Triple(permitted.size, chosen.size - permitted.size, failures)
                }
                musicLightStatus = when {
                    attempted == 0 ->
                        "Musikimpulse pausiert · fehlender Rückkanal oder LAN-Stoppreserve"
                    problems.isEmpty() && paused == 0 ->
                        "$attempted Lampen · Musik-Farbbefehle gesendet (nicht optisch bestätigt)"
                    else ->
                        "${attempted - problems.size}/${chosen.size} Lampen: Befehl gesendet · " +
                            "$paused pausiert · ${problems.size} Übertragungsfehler"
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
        musicAcceptanceEpoch++ // invalidate any pending acceptance timer
        musicStopPending = true
        scope.launch {
            val drained = withTimeoutOrNull(2_500L) {
                while (busy || musicSending) delay(50)
                true
            } == true
            if (!drained && musicSending) {
                // A stuck OutputStream.write is not bounded by soTimeout.
                // Close only the reverse socket; set_music(0) is reserved
                // for the single subsequent stop in haltRunning().
                YeelightLan.interruptMusicOutputs(runningDeviceIps)
            }
            val settled = drained || withTimeoutOrNull(3_000L) {
                while (busy || musicSending) delay(50)
                true
            } == true
            if (!settled) {
                musicLightStatus = "Musikkanal hängt noch – keine weitere Steuerung. " +
                    "Wiederherstellung bleibt gespeichert; Stopp erneut prüfen."
                musicStopPending = false
                return@launch
            }
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
        if (scannerRunning) {
            requestStopScanner()
            scope.launch {
                // Give the scanner a bounded chance to finish OFF verification.
                // Persisted per-bulb recovery remains after a process kill.
                withTimeoutOrNull(40_000L) {
                    while (scannerRunning) delay(100)
                }
                scope.cancel()
            }
            InternalAudioBridge.detach(internalAudioSink)
            microphone?.stop()
            return
        }
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
