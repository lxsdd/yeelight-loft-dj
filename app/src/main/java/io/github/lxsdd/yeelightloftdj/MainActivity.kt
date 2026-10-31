package io.github.lxsdd.yeelightloftdj

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
                    items(SceneLibrary.scenes.take(2), key = { it.id }) { scene ->
                        SceneCard(scene, !controller.busy, controller.activeScene == scene.id) { controller.play(scene) }
                    }
                    item { TextButton(onClick = { page = 1 }) { Text("Alle Szenen entdecken →") } }
                }
                1 -> {
                    item { Heading("Lichtshows", "Farbverläufe direkt auf den Lampen") }
                    item { TargetSelector(controller) }
                    items(SceneLibrary.scenes, key = { it.id }) { scene ->
                        SceneCard(scene, !controller.busy, controller.activeScene == scene.id) { controller.play(scene) }
                    }
                    item { StopButton(controller) }
                    item { Note("Diese Version bietet lokale Farbverläufe. Musikreaktion, Beat-Erkennung und internes Audio folgen in späteren Versionen.") }
                }
                else -> {
                    item { Heading("Deine Geräte", "Direkte Steuerung über dein WLAN") }
                    item { DeviceTools(controller) }
                    items(controller.devices, key = { it.id }) { device -> DeviceCard(device, controller) }
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
        enabled = !controller.busy && controller.selectedCount() > 0,
        onClick = controller::stop,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(Icons.Rounded.Stop, null)
        Spacer(Modifier.width(7.dp))
        Text("Aktuelle Farbverläufe stoppen")
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
            TextButton(onClick = { controller.forget(device) }) { Text("Aus dieser App entfernen") }
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
private fun Note(message: String) {
    Surface(color = Color(0xFF22283B), shape = RoundedCornerShape(16.dp)) {
        Text(message, modifier = Modifier.fillMaxWidth().padding(15.dp), color = muted, fontSize = 12.sp)
    }
}

@Composable
private fun Footer() {
    Text("PRIVAT IM WLAN  ·  KEINE CLOUD  ·  v0.1.1", color = muted, fontSize = 10.sp, letterSpacing = 1.sp)
}
