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

private val loftRooms = setOf("Wohnzimmer", "Esszimmer", "Hobbyraum")
val rooms = listOf("Nicht zugeordnet", "Wohnzimmer", "Esszimmer", "Hobbyraum", "Schlafzimmer", "Masterbad", "Gästebad", "Flur", "Andere")

class LoftController(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("local-room-map", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    var devices by mutableStateOf<List<YeelightDevice>>(emptyList())
        private set
    var assignments by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    var status by mutableStateOf("Bereit · lokales WLAN")
        private set
    var busy by mutableStateOf(false)
        private set
    var target by mutableStateOf("Test (1)")
        private set
    var activeScene by mutableStateOf<String?>(null)
        private set

    fun setTarget(value: String) { if (value in setOf("Test (1)", "Loft", "Alle")) target = value }
    fun roomFor(device: YeelightDevice): String = assignments[device.id] ?: "Nicht zugeordnet"
    fun assign(device: YeelightDevice, room: String) {
        if (room !in rooms) return
        assignments = assignments + (device.id to room)
        prefs.edit().putString("room." + device.id, room).apply()
    }

    fun discover() = task("Suche Yeelights im WLAN …") {
        val found = withContext(Dispatchers.IO) { YeelightLan.discover(app) }
        devices = found
        assignments = found.associate { it.id to (prefs.getString("room." + it.id, "Nicht zugeordnet") ?: "Nicht zugeordnet") }
        activeScene = null
        found.size.toString() + " Geräte gefunden"
    }

    fun addManual(ip: String) {
        val address = ip.trim()
        if (!YeelightLan.isLocalIp(address)) {
            status = "Bitte eine lokale IPv4-Adresse eingeben."
            return
        }
        val device = YeelightLan.manual(address)
        if (devices.none { it.ip == address }) devices = devices + device
        assignments = assignments + (device.id to (prefs.getString("room." + device.id, "Nicht zugeordnet") ?: "Nicht zugeordnet"))
        status = "Gerät hinzugefügt: " + address
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
