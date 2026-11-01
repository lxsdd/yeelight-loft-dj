package io.github.lxsdd.yeelightloftdj

import android.Manifest
import android.app.Activity
import android.content.Context
import android.media.projection.MediaProjectionManager
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

private val shades = listOf(
    Color(0xFF49E0D0), Color(0xFF5DC9FF), Color(0xFF9F9DFB),
    Color(0xFFD9A0FF), Color(0xFFF7A4C8), Color(0xFFFFC588)
)
private val bandLabels = listOf("Bass", "Kick", "Tiefmitte", "Mitten", "Präsenz", "Höhen")

/**
 * A real input monitor. No simulation/placeholder meter is ever displayed as
 * music: all values come from AudioRecord's measured PCM samples.
 */
@Composable
fun MusicScreen(controller: LoftController) {
    val context = LocalContext.current
    var showColor4AcceptanceConfirm by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) controller.startMicrophone()
        else controller.report("Mikrofonberechtigung nicht erteilt · keine Audioaufnahme")
    }
    val projection = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            controller.startInternalAudio(result.resultCode, result.data!!)
        } else controller.report("Interne Audiofreigabe abgelehnt · keine Audioaufnahme")
    }
    val playbackPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager
            projection.launch(manager.createScreenCaptureIntent())
        } else controller.report("Mikrofon-/Playback-Capture-Berechtigung abgelehnt")
    }
    val internalRunning = InternalAudioBridge.running
    val audioRunning = controller.microphoneRunning || internalRunning
    val reading = controller.musicFrame ?: InternalAudioBridge.frame
    val signal = reading?.let { AudioLevelDisplay.from(it.rms, it.peak) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Musik im Loft", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(
            "Analysiere erlaubte Wiedergabe anderer Apps direkt im Android-Audiosystem " +
                "oder nutze das Mikrofon für Lautsprecher und Google Cast.",
            color = Color(0xFFAAB6CE), fontSize = 13.sp
        )
        Surface(color = Color(0xFF1D2232), shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(11.dp)) {
                Text("Interne Audioquelle anderer Apps", color = Color.White,
                    fontWeight = FontWeight.SemiBold)
                Text("BubbleUPnP, YouTube oder andere Player können erfasst werden, " +
                    "wenn Android und die Quell-App die Wiedergabe ausdrücklich freigeben. " +
                    "Kein eigener MP3-Player nötig.", color = Color(0xFFAAB6CE), fontSize = 12.sp)
                if (!internalRunning) {
                    Button(
                        enabled = !controller.microphoneRunning,
                        onClick = {
                            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                                PackageManager.PERMISSION_GRANTED) {
                                val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                                    as MediaProjectionManager
                                projection.launch(manager.createScreenCaptureIntent())
                            } else playbackPermission.launch(Manifest.permission.RECORD_AUDIO)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Internes Android-Audio freigeben") }
                } else {
                    OutlinedButton(onClick = controller::stopInternalAudio,
                        modifier = Modifier.fillMaxWidth()) {
                        Text("Internen Audiomodus stoppen")
                    }
                }
                Text(InternalAudioBridge.status,
                    color = Color(0xFFAAB6CE), fontSize = 12.sp)
                Text("Android zeigt eine Systemfreigabe und bei aktiver Analyse eine " +
                    "Benachrichtigung. Es wird ausschließlich Audio ausgewertet, " +
                    "kein Bildschirmvideo gespeichert.",
                    color = Color(0xFF98A6C0), fontSize = 11.sp)
                Text("Cast auf einen externen Empfänger oder durch die Quell-App " +
                    "gesperrtes Audio liefern möglicherweise Stille. Dafür Mikrofon nutzen.",
                    color = Color(0xFF98A6C0), fontSize = 11.sp)
                Text("Direkte Audioanalyse mit 64-ms-Fenstern. Die App benötigt " +
                    "aktuell einen lebenden Loft-DJ-Controller für Lampenlicht: " +
                    "Hintergrundbetrieb nach App-Wechsel oder Prozessende " +
                    "ist noch nicht dauerhaft abgesichert.",
                    color = Color(0xFF98A6C0), fontSize = 11.sp)
            }
        }

        Surface(color = Color(0xFF1D2232), shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Mic, contentDescription = null,
                        tint = if (controller.microphoneRunning) Color(0xFF67E1C8) else Color(0xFFACB5CC))
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (controller.microphoneRunning) "Mikrofon aktiv" else "Mikrofon ausgeschaltet",
                            color = Color.White, fontWeight = FontWeight.SemiBold
                        )
                        Text("Nur bei geöffneter App · keine Speicherung · keine Cloud",
                            fontSize = 11.sp, color = Color(0xFF98A6C0))
                    }
                }
                if (!controller.microphoneRunning) {
                    Button(
                        enabled = !internalRunning,
                        onClick = {
                            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                                PackageManager.PERMISSION_GRANTED) {
                                controller.startMicrophone()
                            } else {
                                permission.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Rounded.Mic, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Mikrofonanalyse starten")
                    }
                } else {
                    OutlinedButton(
                        onClick = controller::stopMicrophone, modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Rounded.Stop, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Mikrofon stoppen")
                    }
                }
                if (!internalRunning) {
                    Text(
                        "Mikrofon · Analyseempfindlichkeit ×" +
                            String.format(Locale.GERMANY, "%.2f", controller.microphoneAnalysisGain),
                        color = Color.White, fontWeight = FontWeight.Medium, fontSize = 12.sp
                    )
                    Slider(
                        value = controller.microphoneAnalysisGain,
                        onValueChange = controller::updateMicrophoneAnalysisGain,
                        valueRange = 0.5f..4f,
                        steps = 13,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "Wirkt nur auf die lokale Mikrofon-PCM-Analyse, nicht auf Androids " +
                            "Hardware-Mikrofon oder Deezer/BubbleUPnP. Höhere Werte " +
                            "verstärken auch Rauschen und können digital übersteuern. " +
                            "Standard ×1,00; keine zusätzlichen Lampenbefehle.",
                        color = Color(0xFF98A6C0), fontSize = 11.sp
                    )
                }
                if (reading != null && audioRunning) {
                    Text("Live-Frequenzspektrum", color = Color.White, fontWeight = FontWeight.Medium)
                    reading.displayBands.forEachIndexed { index, value ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(bandLabels[index], color = Color(0xFFAAB6CE), fontSize = 11.sp,
                                modifier = Modifier.width(74.dp))
                            LinearProgressIndicator(
                                progress = { value.coerceIn(0f, 1f) },
                                modifier = Modifier.weight(1f).height(9.dp),
                                color = shades[index],
                                trackColor = Color(0xFF323A50),
                            )
                        }
                    }
                    Text(
                        "Bandpegel relativ dargestellt · echte Beat- und Akzenterkennung " +
                            "nutzt weiterhin unveränderte Audio-Messwerte.",
                        color = Color(0xFF98A6C0), fontSize = 11.sp
                    )
                    HorizontalDivider(color = Color(0xFF323A50))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Eingangssignal", color = Color(0xFFAAB6CE),
                            fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Text(signal?.rmsLabel ?: "–",
                            color = Color.White, fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold)
                    }
                    LinearProgressIndicator(
                        progress = { signal?.displayLevel ?: 0f },
                        modifier = Modifier.fillMaxWidth().height(11.dp),
                        color = Color(0xFF67E1C8), trackColor = Color(0xFF323A50)
                    )
                    Text(
                        "Relative digitale Signalanzeige (−60 bis −6 dBFS). " +
                            "3–4 % des PCM-Vollpegels sind bereits etwa −30 bis −28 dBFS – " +
                            "kein Maß für die Lautstärke im Raum.",
                        color = Color(0xFF98A6C0), fontSize = 11.sp
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        MusicMetric("Spitze", signal?.peakLabel ?: "–")
                        MusicMetric("Beats", controller.musicBeatCount.toString())
                        MusicMetric("BPM", reading.estimatedBpm?.toString() ?: "–")
                    }
                    Text(
                        when {
                            reading.beat -> "Bass-Beat im digitalen Signal erkannt"
                            reading.accent == MusicAccent.LOW -> "Tiefer Musikakzent gemessen · kein Beat behauptet"
                            reading.accent == MusicAccent.MID -> "Mitten-Akzent gemessen · kein Beat behauptet"
                            reading.accent == MusicAccent.HIGH -> "Höhen-Akzent gemessen · kein Beat behauptet"
                            else -> "Audio wird gemessen · Beat nur bei erkanntem Bassimpuls"
                        },
                        color = if (reading.beat || reading.accent != null)
                            Color(0xFF67E1C8) else Color(0xFF98A6C0),
                        fontSize = 12.sp
                    )
                } else {
                    Text("Ohne Audioeingang werden bewusst keine Musikdaten simuliert.",
                        fontSize = 12.sp, color = Color(0xFF98A6C0))
                }
            }
        }

        Surface(color = Color(0xFF1D2232), shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = Color(0xFFC3ACFF))
                    Spacer(Modifier.width(9.dp))
                    Text("Musikreaktive Lichtsteuerung",
                        color = Color.White, fontWeight = FontWeight.SemiBold)
                }
                Text(
                    "Optional: Jeder erkannte Bassimpuls kann eine räumlich versetzte Farbe " +
                        "über den ausdrücklich gewählten Zielbereich wandern lassen.",
                    color = Color(0xFFAAB6CE), fontSize = 12.sp
                )
                Text("Kompatible Lampen mit bestätigtem set_music erhalten Farbimpulse über " +
                    "den Yeelight-Musikrückkanal (maximal ca. 3 pro Sekunde). " +
                    "Andere Lampen bleiben bei höchstens einem RGB-Befehl je 1,5 Sekunden " +
                    "und behalten eine Stoppreserve. " +
                    "Keine Stroboskop-Effekte.",
                    color = Color(0xFFAAB6CE), fontSize = 11.sp)
                val acceptanceRefusal = controller.color4MusicAcceptanceRefusal()
                val acceptanceTarget = controller.color4AcceptanceTargetName() ?: "unbekannt"
                OutlinedButton(
                    onClick = { showColor4AcceptanceConfirm = true },
                    enabled = audioRunning && !controller.busy &&
                        !controller.musicLightArmed &&
                        controller.runningDeviceIps.isEmpty() &&
                        acceptanceRefusal == null,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Color4-Einzeltest · 15 Sekunden · automatisch stoppen") }
                Text(
                    acceptanceRefusal ?: "Testziel: $acceptanceTarget · begrenzter Music Mode",
                    color = Color(0xFF98A6C0), fontSize = 11.sp
                )
                val unboundedRefusal = controller.unrestrictedMusicRefusal()
                if (!controller.musicLightArmed && controller.runningDeviceIps.isEmpty()) {
                    Button(
                        onClick = controller::armMusicLights,
                        enabled = audioRunning && !controller.busy &&
                            controller.selectedCount() > 0 &&
                            controller.runningDeviceIps.isEmpty() &&
                            unboundedRefusal == null,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Licht auf ${controller.selectedCount()} Lampen aktivieren") }
                    if (unboundedRefusal != null) {
                        Text(unboundedRefusal,
                            color = Color(0xFFFFD38A), fontSize = 11.sp)
                    }
                } else {
                    OutlinedButton(
                        onClick = controller::disarmMusicLights,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !controller.busy
                    ) { Text("Musiklicht stoppen und vorheriges Licht wiederherstellen") }
                }
                Text(controller.musicLightStatus, color = Color(0xFF98A6C0), fontSize = 12.sp)
                HorizontalDivider(color = Color(0xFF323A50))
                Text("Zwei-Lampen-Eignungsprüfung", color = Color.White,
                    fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(
                    "Nur vorhandene Geräteinformationen. Es werden keine Lampen geschaltet " +
                        "und keine Musikkanäle gestartet.",
                    color = Color(0xFF98A6C0), fontSize = 11.sp
                )
                val pairKey = controller.color4PairSelectionKey()
                var pairReadiness by remember(pairKey) {
                    mutableStateOf<Color4PairCheckResult?>(null)
                }
                OutlinedButton(
                    onClick = { pairReadiness = controller.color4PairReadiness() },
                    enabled = !controller.busy && !controller.musicLightArmed,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Zwei ausgewählte Color4 prüfen") }
                pairReadiness?.let { result ->
                    Surface(
                        color = Color(0xFF2B3144),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text(
                                if (result.eligible) "✓ EIGNUNGSPRÜFUNG BESTANDEN"
                                else "EIGNUNGSPRÜFUNG NICHT BESTANDEN",
                                color = if (result.eligible) Color(0xFF67E1C8)
                                else Color(0xFFFFD38A),
                                fontWeight = FontWeight.Bold, fontSize = 13.sp
                            )
                            Text("Auswahl: " + controller.color4PairSelectionNames(),
                                color = Color.White, fontSize = 12.sp)
                            Text(result.explanation, color = Color(0xFFAAB6CE),
                                fontSize = 12.sp)
                            Text("0 Lampenbefehle · kein Lichttest. " +
                                "Die Stabilität und Wiederherstellung sind damit " +
                                "noch nicht bestätigt.",
                                color = Color(0xFF98A6C0), fontSize = 11.sp)
                        }
                    }
                }
                val dualReadiness = controller.color4DualAcceptanceRefusal()
                Text(
                    dualReadiness ?: "Gemeinsamer Music-Mode nur nach separater Hardwarefreigabe.",
                    color = Color(0xFFFFD38A), fontSize = 11.sp
                )
                // No dual-start action is exposed in this review-only build.
                if (controller.lastColor4TestReport.isNotBlank()) {
                    HorizontalDivider(color = Color(0xFF323A50))
                    Text(
                        if (controller.lastAcceptanceWasDual)
                            "Letzte Zwei-Color4-Session – noch keine optische Bestätigung"
                        else "Letzter Color4-Einzeltest – keine Zwei-Lampen-Messung",
                        color = Color(0xFFAAB6CE), fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp)
                    Text(
                        controller.lastColor4TestReport,
                        color = Color(0xFF67E1C8), fontSize = 12.sp
                    )
                }
                Text("Vor Aktivierung werden alle ursprünglichen Lampenzustände lokal gesichert. " +
                    "Wenn diese nicht lesbar sind, wird kein Licht verändert.",
                    color = Color(0xFF98A6C0), fontSize = 11.sp)
            }
        }

        if (showColor4AcceptanceConfirm) {
            AlertDialog(
                onDismissRequest = { showColor4AcceptanceConfirm = false },
                title = { Text("Color4-Music-Mode-Test") },
                text = {
                    Text("Ausgewählte Lampe: ${controller.color4AcceptanceTargetName() ?: "unbekannt"}. " +
                        "Nur fortfahren, wenn genau diese einzelne Color4 ausdrücklich " +
                        "für den Hardwaretest freigegeben ist. " +
                        "Der Test läuft höchstens 15 Sekunden, sendet höchstens " +
                        "45 RGB-Telegramme über den Rückkanal. Bei messbarer Musik " +
                        "werden echte Bassimpulse oder langsamere Audioaktivitäts-" +
                        "Farbwechsel ausschließlich über den Rückkanal gesendet. " +
                        "Anschließend wird der vorherige Zustand wiederhergestellt. " +
                        "Bei einer Fehlermeldung nicht erneut starten.")
                },
                confirmButton = {
                    TextButton(onClick = {
                        showColor4AcceptanceConfirm = false
                        controller.armColor4MusicAcceptance()
                    }) {
                        Text("Nur ${controller.color4AcceptanceTargetName() ?: "diese Lampe"} testen")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showColor4AcceptanceConfirm = false }) {
                        Text("Abbrechen")
                    }
                }
            )
        }
        Surface(color = Color(0xFF24283B), shape = RoundedCornerShape(18.dp)) {
            Text(
                "Internes Audio: Android AudioPlaybackCapture mit ausdrücklicher Zustimmung. " +
                    "Es werden nur zulässige Audioausgaben analysiert. Bei Google Cast auf " +
                    "einen externen Empfänger steht das Telefon-Mikrofon als Fallback bereit.",
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                color = Color(0xFFAAB6CE), fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun RowScope.MusicMetric(label: String, value: String) {
    Column(
        Modifier.weight(1f).background(Color(0xFF2A3146), RoundedCornerShape(13.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text(label, color = Color(0xFF9EAEC7), fontSize = 11.sp)
        Text(value, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}
