package io.github.lxsdd.yeelightloftdj

import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

/** Non-operational, account-free portable data. Never store IP, tokens or recovery state. */
data class PortableLampRef(val ref: String, val hardwareId: String?, val model: String, val name: String)
data class PortableBackupData(
    val devices: List<PortableLampRef>,
    val rooms: List<String>,
    val assignments: Map<String, String>,
    val groups: Map<String, Set<String>>,
    val order: List<String>,
    val selected: List<String>,
    val scenes: SceneArchiveData,
    val restoreAfterStop: Boolean,
)
data class PortableBackupPreview(
    val newRooms: Int,
    val newGroups: Int,
    val newFlows: Int,
    val newChases: Int,
    val matchedLamps: Int,
    val unmatchedLamps: Int,
    val conflicts: Int,
) {
    fun description(): String =
        "Neue Räume: " + newRooms + ", Gruppen: " + newGroups +
            ", Farbverläufe: " + newFlows + ", Lichtläufe: " + newChases +
            "\nZugeordnete Lampen: " + matchedLamps +
            ", noch nicht zugeordnet: " + unmatchedLamps +
            "\nAbweichende vorhandene Zuordnungen: " + conflicts +
            " (lokale Werte bleiben erhalten)."
}

/**
 * Bounded JSON archive. SHA-256 detects accidental corruption; it is not a
 * signature. The nested payload is base64-encoded to hash the exact bytes.
 */
object PortableConfigArchive {
    const val mimeType = "application/json"
    const val fileName = "yeelight-loft-dj-konfiguration.json"
    const val maxBytes = 256 * 1024
    private const val format = "yeelight-loft-dj-portable-android"
    private const val version = 1
    private val identifier = Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,95}")
    private val ipv4 = Regex("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")

    /** Do not assume IP-based IDs can be used to pair a lamp on another phone. */
    fun stableHardwareId(id: String): String? =
        id.takeIf { value ->
            value.length in 8..96 && identifier.matches(value) &&
                !value.startsWith("ip-", ignoreCase = true) && !ipv4.containsMatchIn(value)
        }

    private fun validName(text: String): Boolean =
        text.isNotBlank() && text.length <= 80 &&
            text.none { it.isISOControl() } && !ipv4.containsMatchIn(text)

    private fun validate(data: PortableBackupData) {
        require(data.devices.size <= 256 && data.rooms.size <= 32 &&
            data.groups.size <= 24) { "Zu viele Geräte, Räume oder Gruppen" }
        val refs = data.devices.map { it.ref }
        require(refs.distinct().size == refs.size && refs.all(identifier::matches))
        require(data.devices.all {
            validName(it.name) && it.model.length <= 100 &&
                (it.hardwareId == null || stableHardwareId(it.hardwareId) == it.hardwareId)
        }) { "Ungültiger Gerätename oder Gerätebezug" }
        val realIds = data.devices.mapNotNull { it.hardwareId }
        require(realIds.distinct().size == realIds.size) { "Doppelte Hardware-ID" }
        require(data.rooms.distinct().size == data.rooms.size &&
            data.rooms.none { it == "Nicht zugeordnet" } && data.rooms.all(::validName))
        require(data.groups.keys.all { NamedLampGroups.validName(it) && validName(it) })
        require(data.groups.values.all { it.size <= 256 && refs.containsAll(it) })
        require(data.assignments.keys.all { it in refs } &&
            data.assignments.values.all { it in data.rooms })
        require(data.order.size <= 256 && data.order.distinct().size == data.order.size &&
            refs.containsAll(data.order))
        require(data.selected.size <= 256 && data.selected.distinct().size == data.selected.size &&
            refs.containsAll(data.selected))
        require(data.scenes.flows.size + data.scenes.chases.size <= 64)
        SceneArchive.encode(data.scenes)
    }

    private fun array(values: Iterable<String>): JSONArray = JSONArray().also { arr ->
        values.forEach { arr.put(it) }
    }

    fun encode(data: PortableBackupData): String {
        validate(data)
        val devices = JSONArray()
        data.devices.forEach { d ->
            devices.put(JSONObject().put("ref", d.ref).put("hardwareId", d.hardwareId ?: "")
                .put("model", d.model).put("name", d.name))
        }
        val groups = JSONArray()
        data.groups.toSortedMap().forEach { (name, ids) ->
            groups.put(JSONObject().put("name", name).put("members", array(ids.sorted())))
        }
        val assignments = JSONObject()
        data.assignments.toSortedMap().forEach { (ref, room) ->
            assignments.put(ref, room)
        }
        val payload = JSONObject()
            .put("devices", devices).put("rooms", array(data.rooms))
            .put("groups", groups).put("assignments", assignments)
            .put("order", array(data.order)).put("selected", array(data.selected))
            .put("scenes", JSONObject(SceneArchive.encode(data.scenes)))
            .put("restoreAfterStop", data.restoreAfterStop)
            .toString()
        val bytes = payload.toByteArray(StandardCharsets.UTF_8)
        val text = JSONObject().put("format", format).put("version", version)
            .put("payloadBase64", Base64.getEncoder().encodeToString(bytes))
            .put("sha256", sha(bytes)).toString(2)
        require(text.toByteArray(StandardCharsets.UTF_8).size <= maxBytes) { "Archiv zu groß" }
        return text
    }

    fun decode(text: String): PortableBackupData {
        require(text.toByteArray(StandardCharsets.UTF_8).size <= maxBytes) { "Datei zu groß" }
        val envelope = JSONObject(text)
        require(envelope.length() == 4 && envelope.getString("format") == format &&
            envelope.getInt("version") == version && envelope.get("version") is Int) {
            "Unbekanntes Backupformat oder Version"
        }
        val encoded = envelope.getString("payloadBase64")
        require(encoded.length <= maxBytes)
        val bytes = Base64.getDecoder().decode(encoded)
        require(bytes.size <= maxBytes)
        val digest = envelope.getString("sha256")
        require(digest.matches(Regex("[0-9a-f]{64}")) && sha(bytes) == digest) {
            "Backup-Prüfsumme stimmt nicht"
        }
        val payload = JSONObject(String(bytes, StandardCharsets.UTF_8))
        require(payload.length() == 8) { "Unbekannte Backup-Felder" }
        val devices = payload.getJSONArray("devices").also { require(it.length() <= 256) }
        val parsedDevices = (0 until devices.length()).map { i ->
            val obj = devices.getJSONObject(i)
            require(obj.length() == 4) { "Unbekanntes Gerätefeld" }
            PortableLampRef(obj.getString("ref"), obj.getString("hardwareId")
                .takeIf { it.isNotBlank() }, obj.getString("model"), obj.getString("name"))
        }
        val rooms = strings(payload.getJSONArray("rooms"), 32)
        val groups = payload.getJSONArray("groups").also { require(it.length() <= 24) }
        val parsedGroups = (0 until groups.length()).map { i ->
            val obj = groups.getJSONObject(i)
            require(obj.length() == 2) { "Unbekanntes Gruppenfeld" }
            obj.getString("name") to strings(obj.getJSONArray("members"), 256).toSet()
        }.toMap()
        require(parsedGroups.size == groups.length()) { "Doppelte Gruppen" }
        val assignments = payload.getJSONObject("assignments")
        require(assignments.length() <= 256)
        val parsedAssignments = assignments.keys().asSequence().associateWith {
            assignments.getString(it)
        }
        val sceneText = payload.getJSONObject("scenes").toString()
        val result = PortableBackupData(
            parsedDevices, rooms, parsedAssignments, parsedGroups,
            strings(payload.getJSONArray("order"), 256),
            strings(payload.getJSONArray("selected"), 256),
            SceneArchive.decode(sceneText), payload.getBoolean("restoreAfterStop")
        )
        validate(result)
        return result
    }

    private fun strings(array: JSONArray, max: Int): List<String> {
        require(array.length() <= max) { "Zu viele Einträge" }
        return (0 until array.length()).map(array::getString)
    }
    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
}
