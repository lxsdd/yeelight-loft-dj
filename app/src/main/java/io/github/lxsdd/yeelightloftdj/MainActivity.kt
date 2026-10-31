package io.github.lxsdd.yeelightloftdj

import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import java.nio.charset.StandardCharsets

private val night = Color(0xFF0D101A)
private val panel = Color(0xFF1D2232)
private val lavender = Color(0xFFC3ACFF)
private val muted = Color(0xFF9AA5BE)

class MainActivity : ComponentActivity() {
    private lateinit var controller: LoftController
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = LoftController(applicationContext)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = lavender,
                secondary = Color(0xFF76D7D4),
                background = night,
                surface = panel,
                onBackground = Color.White,
            )) { LoftApp(controller) }
        }
    }
    override fun onDestroy() {
        controller.close()
        super.onDestroy()
    }
}

@Composable
private fun LoftApp(controller: LoftController) {
    var page by remember { mutableIntStateOf(0) }
    Scaffold(
        containerColor = night,
        bottomBar = {
            NavigationBar(containerColor = panel) {
                val pages = listOf("Home", "Szenen", "Geräte")
                val icons = listOf(Icons.Rounded.Home, Icons.Rounded.AutoAwesome, Icons.Rounded.Devices)
                pages.forEachIndexed { index, label ->
                    NavigationBarItem(
                        selected = page == index,
                        onClick = { page = index },
                        icon = { Icon(icons[index], contentDescription = null) },
                        label = { Text(label) },
                    )
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { Header() }
            item { Status(controller) }
            when (page) {
                0 -> {
                    item { Hero(controller, onScene = { page = 1 }, onDevice = { page = 2 }) }
                    item { Heading("Deine Lichtwelten", "Ein Tipp – und dein Loft verwandelt sich.") }
                    item { TargetSelector(controller) }
                    item { SceneRestoreOption(controller) }
                    item { TargetPowerControls(controller) }
                    items(SceneLibrary.scenes.take(2), key = { it.id }) { scene ->
                        SceneCard(scene, !controller.busy, controller.activeScene == scene.id) { controller.play(scene) }
                    }
                    item { TextButton(onClick = { page = 1 }) { Text("Alle Szenen entdecken →") } }
                }
                1 -> {
                    item { Heading("Lichtshows", "Farbverläufe direkt auf den Lampen") }
                    item { TargetSelector(controller) }
                    item { SceneRestoreOption(controller) }
                    items(controller.allScenes(), key = { it.id }) { scene ->
                        SceneCard(scene, !controller.busy, controller.activeScene == scene.id) { controller.play(scene) }
                    }
                    item { Heading("Lichtlauf-Studio", "Alle Leuchten in Bewegung – ohne Kommandoflut") }
                    item { ChasePanel(controller) }
                    item { SceneExchangePanel(controller) }
                    item { StopButton(controller) }
                    item { Note("Lichtläufe nutzen Yeelight-native Farbsequenzen. Tempo und Schweif wirken pro Lampe; der Start vieler Lampen ist nicht millisekundengenau synchron. Musikreaktion folgt später.") }
                }
                else -> {
                    item { Heading("Deine Geräte", "Direkte Steuerung über dein WLAN") }
                    item { DeviceTools(controller) }
                    items(controller.orderedDevices(), key = { it.id }) { device -> DeviceCard(device, controller) }
                    if (controller.devices.isEmpty()) {
                        item { Note("Noch keine Geräte gefunden. Suche im WLAN oder füge eine lokale IP hinzu. LAN-Steuerung muss in Yeelight aktiviert sein.") }
                    }
                }
            }
            item { Footer() }
        }
    }
}

@Composable
private fun Header() {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(16.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF8061E3), Color(0xFF2DC8BE)))),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = Color.White) }
        Spacer(Modifier.width(12.dp))
        Column {
            Text("YEELIGHT", color = muted, fontSize = 10.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
            Text("LOFT DJ", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
        }
        Spacer(Modifier.weight(1f))
        Text("PREVIEW", color = lavender, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Status(controller: LoftController) {
    Surface(shape = RoundedCornerShape(17.dp), color = Color(0xFF252A3A)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (controller.busy) CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
            else Icon(Icons.Rounded.Lightbulb, null, tint = Color(0xFF7EDBC7), modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(10.dp))
            Text(controller.status, color = Color.White, fontSize = 13.sp, maxLines = 3)
        }
    }
}

@Composable
private fun Hero(controller: LoftController, onScene: () -> Unit, onDevice: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF312560), Color(0xFF174F69), Color(0xFF1B5649))))
            .padding(22.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
            Text("DEIN LOFT. DEIN LICHT.", color = Color(0xFFC3E4F3), fontSize = 11.sp, letterSpacing = 1.6.sp)
            Text("Mach Licht zu\neinem Erlebnis.", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 29.sp, lineHeight = 34.sp)
            Text("Wohnzimmer · Esszimmer · Hobbyraum", color = Color(0xFFE1E7F2), fontSize = 13.sp)
            Text(controller.devices.size.toString() + " Geräte erkannt", color = Color.White, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onScene) {
                    Icon(Icons.Rounded.PlayArrow, null)
                    Spacer(Modifier.width(4.dp))
                    Text("Szenen")
                }
                OutlinedButton(onClick = onDevice) { Text("Geräte") }
            }
        }
    }
}

@Composable
private fun Heading(name: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(name, fontSize = 22.sp, color = Color.White, fontWeight = FontWeight.Bold)
        Text(subtitle, fontSize = 13.sp, color = muted)
    }
}

@Composable
private fun SceneCard(scene: LoftScene, enabled: Boolean, active: Boolean, onPlay: () -> Unit) {
    Card(
        onClick = onPlay,
        enabled = enabled,
        colors = CardDefaults.cardColors(containerColor = if (active) Color(0xFF302D47) else panel),
        shape = RoundedCornerShape(24.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(122.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.width(104.dp).fillMaxHeight()
                    .background(Brush.linearGradient(scene.colors.map { Color(it) })),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Rounded.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(35.dp)) }
            Column(Modifier.weight(1f).padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(scene.name, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text(scene.description, color = muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(if (active) "AKTIV" else "STARTEN  ▶", color = lavender, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun TargetSelector(controller: LoftController) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text("Zielbereich · " + controller.target + " (" + controller.selectedCount() + ")", color = Color.White, fontWeight = FontWeight.Medium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Test (1)", "Loft", "Alle").forEach { value ->
                FilterChip(selected = controller.target == value, onClick = { controller.chooseTarget(value) }, label = { Text(value) })
            }
        }
        Text("Zum sicheren Test zuerst nur eine Lampe wählen. Loft benötigt die Raumzuordnung unter Geräte.", color = muted, fontSize = 12.sp)
    }
}

@Composable
private fun StopButton(controller: LoftController) {
    OutlinedButton(
        enabled = !controller.busy && (controller.runningDeviceIps.isNotEmpty() || controller.selectedCount() > 0),
        onClick = controller::stop,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(Icons.Rounded.Stop, null)
        Spacer(Modifier.width(7.dp))
        Text("Alle laufenden Lichtshows stoppen")
    }
}

@Composable
private fun DeviceTools(controller: LoftController) {
    var ip by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = controller::discover, enabled = !controller.busy, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.Search, null)
            Spacer(Modifier.width(7.dp))
            Text("Alle Lampen suchen")
        }
        OutlinedButton(onClick = controller::deepDiscover, enabled = !controller.busy, modifier = Modifier.fillMaxWidth()) {
            Text("Intensivsuche im WLAN")
        }
        Text("Die Hauptsuche kombiniert Geräteankündigungen und direkte LAN-Prüfung. Die Intensivsuche ist als gezielter Wiederholungstest verfügbar.", color = muted, fontSize = 12.sp)
        Surface(color = Color(0xFF252A3D), shape = RoundedCornerShape(14.dp)) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("Netzwerk-Diagnose", color = Color.White, fontWeight = FontWeight.SemiBold)
                Text(controller.searchDiagnostics, color = Color(0xFFB6C4E7), fontSize = 12.sp)
                Text("Yeelight nutzt SSDP-ähnliches UDP-Multicast (nicht mDNS). Antworten kommen einzeln per Unicast; Direkt-LAN prüft unabhängig davon TCP 55443. Je Suchrunde werden unterschiedliche Lampen gezählt.", color = muted, fontSize = 11.sp)
            }
        }
        OutlinedButton(
            onClick = controller::compareDirectedUdp,
            enabled = !controller.busy && controller.missingMulticastCount() > 0,
            modifier = Modifier.fillMaxWidth()
        ) { Text("UDP-Gegenprobe für " + controller.missingMulticastCount() + " Nichtantworter") }
        if (controller.directedUdpDiagnostics.isNotBlank()) {
            Text(controller.directedUdpDiagnostics, color = Color(0xFFB6C4E7), fontSize = 12.sp)
        }
        Text("Die UDP-Gegenprobe ist experimentell und verändert keine Lampe. Nur positive Antworten sind aussagekräftig; negative Antworten beweisen keinen Defekt.",
            color = muted, fontSize = 11.sp)
        Text("Gespeicherte Geräte bleiben bei erneuter Suche erhalten. Nicht bestätigte Geräte können inzwischen offline sein.", color = muted, fontSize = 12.sp)
        OutlinedTextField(
            value = ip,
            onValueChange = { ip = it },
            label = { Text("Lokale IP, falls Suche nicht funktioniert") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedButton(onClick = { controller.addManual(ip); ip = "" }, modifier = Modifier.fillMaxWidth()) {
            Text("Gerät manuell hinzufügen")
        }
    }
}

@Composable
private fun DeviceCard(device: YeelightDevice, controller: LoftController) {
    var localAlias by remember(device.id) { mutableStateOf(controller.aliasFor(device)) }
    Surface(color = panel, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Lightbulb, null, tint = lavender)
                Spacer(Modifier.width(9.dp))
                Column {
                    Text(controller.displayName(device), fontWeight = FontWeight.Bold, color = Color.White)
                    Text(if (YeelightLan.isSpecificModel(device.model)) "Modell: " + device.model
                        else "Modell über LAN nicht ermittelbar", color = muted, fontSize = 12.sp)
                    Text(device.ip + ":" + device.port, fontSize = 12.sp, color = muted)
                }
            }
            OutlinedTextField(
                value = localAlias, onValueChange = { localAlias = it.take(50) },
                label = { Text("Eigener Anzeigename (nur in dieser App)") },
                placeholder = { Text("z. B. Billardtisch links") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            TextButton(onClick = { controller.setAlias(device, localAlias) }) {
                Text("Anzeigenamen speichern")
            }
            Text(if (device.ip in controller.lastSeenIps) "Bei letzter Suche bestätigt" else "Gespeichert · aktuell nicht überprüft",
                color = if (device.ip in controller.lastSeenIps) Color(0xFF80D9C6) else muted, fontSize = 12.sp)
            val discoveryMethod = when {
                device.ip in controller.lastMulticastIps && device.ip in controller.lastDirectIps -> "Multicast + Direkt-LAN"
                device.ip in controller.lastMulticastIps -> "Multicast"
                device.ip in controller.lastDirectIps -> "Nur Direkt-LAN"
                else -> "Bei diesem Suchlauf nicht bestätigt"
            }
            Text("Erkennung: " + discoveryMethod, color = muted, fontSize = 11.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Reihenfolge im Lauflicht", fontSize = 12.sp, color = muted, modifier = Modifier.weight(1f))
                val position = controller.orderedDevices().indexOfFirst { it.id == device.id }
                OutlinedButton(onClick = { controller.moveLamp(device, -1) },
                    enabled = !controller.busy && position > 0) { Text("↑") }
                OutlinedButton(onClick = { controller.moveLamp(device, 1) },
                    enabled = !controller.busy && position < controller.devices.lastIndex) { Text("↓") }
            }
            LampManualPanel(device, controller)
            TextButton(onClick = { controller.forget(device) }, enabled = !controller.busy) { Text("Aus dieser App entfernen") }
            if (!device.canFlow) Note("Dieses Gerät meldet keine Unterstützung für start_cf.")
            Text("Raum: " + controller.roomFor(device), color = Color.White, fontSize = 12.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                rooms.forEach { room ->
                    FilterChip(
                        selected = controller.roomFor(device) == room,
                        onClick = { controller.assign(device, room) },
                        label = { Text(room) }
                    )
                }
            }
        }
    }
}


@Composable
private fun TargetPowerControls(controller: LoftController) {
    Surface(color = panel, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Schnellsteuerung", color = Color.White, fontWeight = FontWeight.SemiBold)
            Text("Für ${controller.target} · ${controller.selectedCount()} Lampen. Farbverläufe werden durch manuelles Schalten beendet.", color = muted, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Button(
                    onClick = { controller.powerTarget(true) },
                    enabled = !controller.busy && controller.selectedCount() > 0,
                    modifier = Modifier.weight(1f)
                ) { Text("Einschalten") }
                OutlinedButton(
                    onClick = { controller.powerTarget(false) },
                    enabled = !controller.busy && controller.selectedCount() > 0,
                    modifier = Modifier.weight(1f)
                ) { Text("Ausschalten") }
            }
        }
    }
}

@Composable
private fun LampManualPanel(device: YeelightDevice, controller: LoftController) {
    var expanded by remember(device.ip) { mutableStateOf(false) }
    val live = controller.snapshotFor(device)
    OutlinedButton(
        onClick = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth()
    ) { Text(if (expanded) "Lichtsteuerung schließen" else "Lichtsteuerung öffnen") }
    if (!expanded) return

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    live == null -> "Status noch nicht abgerufen"
                    live.isOn -> "Eingeschaltet · ${live.brightness} %"
                    else -> "Ausgeschaltet"
                },
                color = if (live?.isOn == true) Color(0xFF80D9C6) else muted,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { controller.refreshLamp(device) }, enabled = !controller.busy) {
                Text("Aktualisieren")
            }
        }
        if (controller.activeScene != null) {
            Text("Lichtshow zuerst stoppen, bevor du einzelne Farben oder Helligkeit änderst.",
                color = muted, fontSize = 11.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { controller.controlLamp(device, LampCommands.power(true), "Einschalten") },
                enabled = !controller.busy && controller.activeScene == null,
                modifier = Modifier.weight(1f)
            ) { Text("Ein") }
            OutlinedButton(
                onClick = { controller.controlLamp(device, LampCommands.power(false), "Ausschalten") },
                enabled = !controller.busy && controller.activeScene == null,
                modifier = Modifier.weight(1f)
            ) { Text("Aus") }
        }
        var brightness by remember(device.ip, live?.brightness) {
            mutableFloatStateOf((live?.brightness ?: 60).toFloat())
        }
        Text("Helligkeit · ${brightness.roundToInt()} %", color = Color.White, fontSize = 12.sp)
        Slider(
            value = brightness,
            onValueChange = { brightness = it },
            onValueChangeFinished = {
                controller.controlLamp(device, LampCommands.brightness(brightness.roundToInt()), "Helligkeit")
            },
            valueRange = 1f..100f,
            enabled = !controller.busy && controller.activeScene == null,
        )
        val colors = listOf(
            Triple("Warm", 0xFFAE62, Color(0xFFFFAE62)),
            Triple("Türkis", 0x23D7D0, Color(0xFF23D7D0)),
            Triple("Violett", 0xA47BFF, Color(0xFFA47BFF)),
            Triple("Pink", 0xFF55B4, Color(0xFFFF55B4)),
        )
        if (device.methods.isEmpty() || "set_rgb" in device.methods) {
            Text("Direktfarben", color = Color.White, fontSize = 12.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                colors.forEach { (name, rgb, tint) ->
                    FilterChip(
                        selected = live?.colorMode == 1 && live.rgb == rgb,
                        onClick = { controller.controlLamp(device, LampCommands.rgb(rgb), name) },
                        enabled = !controller.busy && controller.activeScene == null,
                        label = { Text(name) },
                        leadingIcon = {
                            Box(Modifier.size(11.dp).clip(RoundedCornerShape(5.dp)).background(tint))
                        },
                    )
                }
            }
        }
        if (device.methods.isEmpty() || "set_ct_abx" in device.methods) {
            var kelvin by remember(device.ip, live?.temperature) {
                mutableFloatStateOf((live?.temperature ?: 3500).toFloat())
            }
            Text("Weißtemperatur · ${kelvin.roundToInt()} K", color = Color.White, fontSize = 12.sp)
            Slider(
                value = kelvin,
                onValueChange = { kelvin = it },
                onValueChangeFinished = {
                    controller.controlLamp(device, LampCommands.temperature(kelvin.roundToInt()), "Weißtemperatur")
                },
                valueRange = 1700f..6500f,
                enabled = !controller.busy && controller.activeScene == null,
            )
        }
        OutlinedButton(
            onClick = { controller.identifyLamp(device) },
            enabled = !controller.busy && controller.activeScene == null,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Diese Lampe kurz identifizieren") }
        Text(
            "Identifikation nur ohne laufende Lichtshow. Vorherigen Zustand lesen und anschließend wiederherstellen. Bei unvollständig lesbarem Farbzustand wird kein Testsignal gesendet.",
            color = muted, fontSize = 11.sp
        )
    }
}


@Composable
private fun ChasePanel(controller: LoftController) {
    var style by remember { mutableStateOf(ChaseStyle.RAINBOW_TAIL) }
    var choice by remember { mutableStateOf("Alle") }
    var direction by remember { mutableStateOf(ChaseDirection.FORWARD) }
    var bounce by remember { mutableStateOf(false) }
    var palette by remember { mutableStateOf("#FF3ABF, #FFB12C, #5A4BFF, #25D9E6, #FFE474") }
    var ms by remember { mutableFloatStateOf(450f) }
    var tail by remember { mutableFloatStateOf(4f) }
    var intensity by remember { mutableFloatStateOf(75f) }
    var name by remember { mutableStateOf("Meine Sternschnuppe") }
    var previewStep by remember { mutableFloatStateOf(0f) }
    val parsedPalette = remember(palette) { runCatching { ChaseCompiler.decodePalette(palette) } }
    val config = parsedPalette.getOrNull()?.let { colors ->
        ChaseConfig(style, colors, ms.roundToInt(), tail.roundToInt(),
            intensity.roundToInt(), direction, bounce)
    }
    Surface(color = panel, shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Effekt", color = Color.White, fontWeight = FontWeight.SemiBold)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                listOf(ChaseStyle.RAINBOW_TAIL to "Regenbogenschweif",
                    ChaseStyle.METEOR to "Sternschnuppe", ChaseStyle.COLOR_WAVE to "Farbwelle")
                    .forEach { (value, label) ->
                        FilterChip(selected = style == value, onClick = { style = value }, label = { Text(label) })
                    }
            }
            Text("Zielbereich", color = Color.White, fontWeight = FontWeight.SemiBold)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                listOf("Test (1)", "Loft", "Alle").forEach { value ->
                    FilterChip(selected = choice == value, onClick = { choice = value }, label = { Text(value) })
                }
            }
            Text(
                "Die Laufrichtung folgt der Reihenfolge in Geräte. Bei „Alle“ werden alle gespeicherten Lampen berücksichtigt – auch eventuell offline befindliche.",
                color = muted, fontSize = 11.sp
            )
            Text("Farbpalette · RGB-Hex, durch Komma getrennt", color = Color.White, fontSize = 12.sp)
            OutlinedTextField(
                value = palette,
                onValueChange = { palette = it.take(180) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                label = { Text("Farben (#RRGGBB)") },
            )
            if (parsedPalette.isFailure) {
                Text(parsedPalette.exceptionOrNull()?.message ?: "Ungültige Farben",
                    color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            } else {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    parsedPalette.getOrNull().orEmpty().forEach { rgb ->
                        Box(Modifier.size(26.dp).clip(RoundedCornerShape(9.dp))
                            .background(Color(0xFF000000L or rgb.toLong())))
                    }
                }
            }
            if (config != null && controller.previewCount(choice) > 0) {
                val lampCount = controller.previewCount(choice).coerceAtMost(24)
                val count = ChaseCompiler.frames(config, 0, lampCount).size
                val step = previewStep.roundToInt().coerceIn(0, count - 1)
                Text("Effektvorschau · Schritt ${step + 1} von $count",
                    color = Color.White, fontSize = 12.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    repeat(lampCount) { index ->
                        val frame = ChaseCompiler.frames(config, index, lampCount)[step]
                        val tinted = Color(0xFF000000L or frame.rgb.toLong())
                        Box(
                            Modifier.size(23.dp).clip(RoundedCornerShape(50))
                                .background(tinted.copy(alpha = 0.2f + 0.8f * frame.brightness / 100f))
                        )
                    }
                }
                Slider(
                    value = previewStep.coerceIn(0f, (count - 1).toFloat()),
                    onValueChange = { previewStep = it },
                    valueRange = 0f..(count - 1).toFloat()
                )
                Text("Simulation der Reihenfolge – keine Statusanzeige der echten Lampen.",
                    color = muted, fontSize = 11.sp)
            }
            Text("Tempo · ${ms.roundToInt()} ms pro Lampe", color = Color.White, fontSize = 12.sp)
            Slider(value = ms, onValueChange = { ms = it }, valueRange = 180f..2500f)
            Text("Schweiflänge · ${tail.roundToInt()} Lampen", color = Color.White, fontSize = 12.sp)
            Slider(value = tail, onValueChange = { tail = it }, valueRange = 1f..16f)
            Text("Maximale Helligkeit · ${intensity.roundToInt()} %", color = Color.White, fontSize = 12.sp)
            Slider(value = intensity, onValueChange = { intensity = it }, valueRange = 5f..100f)
            Text("Laufrichtung", color = Color.White, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                FilterChip(selected = direction == ChaseDirection.FORWARD,
                    onClick = { direction = ChaseDirection.FORWARD }, label = { Text("Vorwärts") })
                FilterChip(selected = direction == ChaseDirection.REVERSE,
                    onClick = { direction = ChaseDirection.REVERSE }, label = { Text("Rückwärts") })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = bounce, onCheckedChange = { bounce = it })
                Text("Pingpong · an den Enden umkehren", color = Color.White, fontSize = 12.sp)
            }
            Button(
                onClick = { if (config != null) controller.playChase(config, choice) },
                enabled = !controller.busy && config != null && controller.devices.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) { Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Lichtlauf starten") }
            Text("Hinweis: Schnell wechselnde Farben können für lichtempfindliche Personen ungeeignet sein.",
                color = muted, fontSize = 11.sp)
            HorizontalDivider(color = Color(0xFF49475F))
            OutlinedTextField(
                value = name, onValueChange = { name = it.take(64) },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Name der eigenen Szene") }
            )
            OutlinedButton(
                onClick = {
                    if (config != null) runCatching { controller.saveChase(name, config) }
                        .onFailure { controller.report("Speichern fehlgeschlagen: " + it.message) }
                },
                enabled = config != null && name.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Aktuelle Einstellungen als Szene speichern") }
            if (controller.savedChases.isNotEmpty()) {
                Text("Gespeicherte Lichtläufe", color = Color.White, fontWeight = FontWeight.SemiBold)
                controller.savedChases.forEach { saved ->
                    Surface(color = Color(0xFF2A2F43), shape = RoundedCornerShape(12.dp)) {
                        Column(Modifier.fillMaxWidth().padding(11.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(saved.name, color = Color.White, fontWeight = FontWeight.Medium)
                            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                TextButton(onClick = {
                                    val c = saved.config
                                    style = c.style
                                    palette = ChaseCompiler.encodePalette(c.palette)
                                    ms = c.stepMs.toFloat()
                                    tail = c.tailLength.toFloat()
                                    intensity = c.brightness.toFloat()
                                    direction = c.direction
                                    bounce = c.bounce
                                    name = saved.name
                                }) { Text("Laden") }
                                TextButton(onClick = {
                                    controller.playChase(saved.config, choice, saved.name)
                                }, enabled = !controller.busy && controller.devices.isNotEmpty()) {
                                    Text("Starten")
                                }
                                TextButton(onClick = { controller.deleteChase(saved) },
                                    enabled = !controller.busy) { Text("Löschen") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SceneRestoreOption(controller: LoftController) {
    Surface(color = panel, shape = RoundedCornerShape(17.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Nach Stopp vorheriges Licht wiederherstellen",
                        color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text("Ein/Aus, Farbe und Helligkeit jeder Lampe werden vor der Szene erfasst.",
                        color = muted, fontSize = 11.sp)
                }
                Switch(
                    checked = controller.restoreAfterStop,
                    onCheckedChange = controller::setRestoreAfterStop,
                    enabled = !controller.busy && controller.runningDeviceIps.isEmpty()
                )
            }
            Text(
                "Wenn ein Zustand nicht sicher gelesen werden kann, startet die Szene nicht. Wiederherstellung erfolgt beim Stoppen, nicht automatisch nach einer festen Zeit.",
                color = muted, fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun SceneExchangePanel(controller: LoftController) {
    val context = LocalContext.current
    var pendingExport by remember { mutableStateOf<String?>(null) }
    var pendingImport by remember { mutableStateOf<SceneArchiveData?>(null) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(SceneArchive.mimeType)
    ) { uri ->
        val payload = pendingExport
        if (uri != null && payload != null) runCatching {
            val bytes = payload.toByteArray(StandardCharsets.UTF_8)
            context.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
                stream.write(bytes)
                stream.flush()
            } ?: error("Zieldatei nicht beschreibbar")
        }.onSuccess {
            controller.report("Szenen erfolgreich als JSON exportiert")
        }.onFailure {
            controller.report("Export fehlgeschlagen: " + it.message)
        }
        pendingExport = null
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            val bytes = context.contentResolver.openInputStream(uri)?.use { stream ->
                val buffer = ByteArray(SceneArchive.maxBytes + 1)
                var total = 0
                while (total < buffer.size) {
                    val read = stream.read(buffer, total, buffer.size - total)
                    if (read < 0) break
                    total += read
                }
                require(total <= SceneArchive.maxBytes) { "Importdatei ist zu groß" }
                String(buffer, 0, total, StandardCharsets.UTF_8)
            } ?: error("Datei nicht lesbar")
            SceneArchive.decode(bytes)
        }.onSuccess { archive ->
            pendingImport = archive
            selected = archive.flows.map { "flow:" + it.id }.toSet() +
                archive.chases.map { "chase:" + it.id }.toSet()
        }.onFailure { controller.report("Import fehlgeschlagen: " + it.message) }
    }

    Surface(color = panel, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Szenen sichern und übertragen", color = Color.White, fontWeight = FontWeight.SemiBold)
            Text("Gesamte Sammlung oder einzelne Szenen – lokale JSON-Dateien ohne IP-Adressen, Geräte-IDs oder Grundrisse.",
                color = muted, fontSize = 12.sp)
            Button(onClick = {
                pendingExport = controller.exportSceneArchive()
                exportLauncher.launch(SceneArchive.fileName)
            }, modifier = Modifier.fillMaxWidth()) {
                Text("Alle Szenen exportieren")
            }
            OutlinedButton(onClick = {
                importLauncher.launch(arrayOf(SceneArchive.mimeType, "text/plain"))
            }, modifier = Modifier.fillMaxWidth()) {
                Text("Szenen importieren · einzeln auswählen")
            }
            Text("Einzeln exportieren", color = Color.White, fontWeight = FontWeight.SemiBold)
            controller.allScenes().forEach { scene ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(scene.name, color = Color.White, fontSize = 12.sp,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        pendingExport = controller.exportSingleFlow(scene)
                        exportLauncher.launch("yeelight-flow-" + scene.id + ".json")
                    }) { Text("Export") }
                }
            }
            controller.savedChases.forEach { preset ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(preset.name, color = Color.White, fontSize = 12.sp,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        pendingExport = controller.exportSingleChase(preset)
                        exportLauncher.launch("yeelight-chase-" + preset.id + ".json")
                    }) { Text("Export") }
                }
            }
            Text("Identische Szenen werden beim Import übersprungen; deine vorhandenen bleiben erhalten.",
                color = muted, fontSize = 11.sp)
        }
    }
    pendingImport?.let { archive ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("Szenen zum Import auswählen") },
            text = {
                Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
                    Text("Nur markierte Szenen werden übernommen.",
                        fontSize = 12.sp)
                    archive.flows.forEach { scene ->
                        val key = "flow:" + scene.id
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = key in selected,
                                onCheckedChange = { checked ->
                                    selected = if (checked) selected + key else selected - key
                                })
                            Text(scene.name + " · Farbverlauf", fontSize = 12.sp)
                        }
                    }
                    archive.chases.forEach { preset ->
                        val key = "chase:" + preset.id
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = key in selected,
                                onCheckedChange = { checked ->
                                    selected = if (checked) selected + key else selected - key
                                })
                            Text(preset.name + " · Lichtlauf", fontSize = 12.sp)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = selected.isNotEmpty(),
                    onClick = {
                        runCatching {
                            val subset = SceneArchiveData(
                                archive.flows.filter { "flow:" + it.id in selected },
                                archive.chases.filter { "chase:" + it.id in selected }
                            )
                            controller.importSceneArchive(SceneArchive.encode(subset))
                        }.onSuccess { controller.report(it); pendingImport = null }
                            .onFailure { controller.report("Import fehlgeschlagen: " + it.message) }
                    }
                ) { Text("Auswahl importieren") }
            },
            dismissButton = {
                TextButton(onClick = { pendingImport = null }) { Text("Abbrechen") }
            }
        )
    }
}

@Composable
private fun Note(message: String) {
    Surface(color = Color(0xFF22283B), shape = RoundedCornerShape(16.dp)) {
        Text(message, modifier = Modifier.fillMaxWidth().padding(15.dp), color = muted, fontSize = 12.sp)
    }
}

@Composable
private fun Footer() {
    Text("PRIVAT IM WLAN  ·  KEINE CLOUD  ·  v0.1.1", color = muted, fontSize = 10.sp, letterSpacing = 1.sp)
}
