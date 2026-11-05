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
    var dualDialogSelectionKey by remember { mutableStateOf<String?>(null) }
    var liveDjStyle by remember { mutableStateOf(Color4LiveStyle.BEAT) }
    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) controller.startMicrophone()
        else controller.report("Mikrofonberechtigung nicht erteilt · keine Audioaufnahme")
    }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // A denied notification is not permission to do hidden capture.
        // The service stays visible to Android's active-app manager and the
        // in-app STOP remains available; no automatic background start.
        controller.startForegroundColor4LiveDjGroupAware(liveDjStyle)
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
                        Text("Live-DJ läuft auch bei Displaysperre · keine Speicherung · keine Cloud",
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
                    Text("Android-Mikrofonprofil · nur bei gestoppter Aufnahme wechseln",
                        color = Color.White, fontWeight = FontWeight.Medium, fontSize = 12.sp)
                    var profileMenuExpanded by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(
                            onClick = { profileMenuExpanded = true },
                            enabled = !controller.microphoneRunning,
                        ) { Text(controller.microphoneInputProfile.title) }
                        DropdownMenu(
                            expanded = profileMenuExpanded,
                            onDismissRequest = { profileMenuExpanded = false }
                        ) {
                            controller.microphoneProfiles.forEach { profile ->
                                DropdownMenuItem(
                                    text = { Text(profile.title) },
                                    onClick = {
                                        profileMenuExpanded = false
                                        controller.updateMicrophoneInputProfile(profile)
                                    }
                                )
                            }
                        }
                    }
                    Text(controller.microphoneInputProfile.description,
                        color = Color(0xFF98A6C0), fontSize = 11.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Automatische Analyseverstärkung (begrenzt)",
                            modifier = Modifier.weight(1f),
                            color = Color.White, fontSize = 12.sp)
                        Switch(
                            checked = controller.automaticMicrophoneGain,
                            onCheckedChange = controller::updateAutomaticMicrophoneGain,
                        )
                    }
                    if (controller.automaticMicrophoneGain) {
                        Text("Nur Software-Analyse, kein Hardware-Mikrofon-Gain. " +
                            "Maximal ×4, Rauschen wird nicht automatisch hochgeregelt. " +
                            "Änderungen der Verstärkung erzeugen keine künstlichen Beats.",
                            color = Color(0xFF98A6C0), fontSize = 11.sp)
                    }
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
                        enabled = !controller.automaticMicrophoneGain,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "Wirkt nur auf die lokale Mikrofon-PCM-Analyse, nicht auf Androids " +
                            "Hardware-Mikrofon oder Deezer/BubbleUPnP. Höhere Werte " +
                            "verstärken auch Rauschen und können digital übersteuern. " +
                            "Standard ×1,00; keine zusätzlichen Lampenbefehle.",
                        color = Color(0xFF98A6C0), fontSize = 11.sp
                    )
                    if (controller.microphoneRunning) {
                        controller.microphoneDiagnostics?.let { diagnostic ->
                            Text(
                                "Android-Rohpegel (vor ×-Verstärkung): RMS " +
                                    String.format(Locale.GERMANY, "%.1f", diagnostic.rawRmsDbfs) +
                                    " dBFS · Spitze " +
                                    String.format(Locale.GERMANY, "%.1f", diagnostic.rawPeakDbfs) +
                                    " dBFS",
                                color = Color.White, fontSize = 12.sp
                            )
                            Text(
                                "Analyse ×" +
                                    String.format(Locale.GERMANY, "%.2f", diagnostic.gain) +
                                    " · Roh-Clipping " + diagnostic.rawClipping +
                                    " · Digital-Clipping " + diagnostic.outputClipping +
                                    " Samples/Frame",
                                color = Color(0xFF98A6C0), fontSize = 11.sp
                            )
                            Text(
                                "Aufnahmeroute: " + diagnostic.routedInput,
                                color = Color(0xFF98A6C0), fontSize = 11.sp
                            )
                        }
                    }
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
                            Spacer(Modifier.width(5.dp))
                            Text(
                                String.format(Locale.GERMANY, "%.3f", reading.bands[index]),
                                color = Color(0xFF98A6C0), fontSize = 10.sp,
                                modifier = Modifier.width(40.dp)
                            )
                        }
                    }
                    Text(
                        "Balken = relative Ansicht; Zahl = gemessener normierter FFT-Bandwert. " +
                            "Kein dBFS-Wert. Echte Beat- und Akzenterkennung nutzt " +
                            "weiterhin unveränderte Audio-Messwerte.",
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
                val probeRefusal = controller.minimalProbeRefusal()
                val liveDjRefusal = controller.liveDjRefusal(liveDjStyle)
                var showMinimalProbeConfirm by remember { mutableStateOf(false) }
                var showTempoProbeConfirm by remember { mutableStateOf(false) }
                var showLiveDjConfirm by remember { mutableStateOf(false) }

                Text("Color4 Live-DJ · 1–4 Music-Rückkanäle",
                    color = Color.White, fontWeight = FontWeight.SemiBold)
                Text("Lichtstil vor dem Start wählen",
                    color = Color(0xFFAAB6CE), fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Color4LiveStyle.entries.forEach { style ->
                        FilterChip(
                            selected = (controller.activeColor4LiveStyle ?: liveDjStyle) == style,
                            enabled = !controller.color4LiveDjRunning && !controller.busy,
                            onClick = { liveDjStyle = style },
                            label = { Text(style.label) }
                        )
                    }
                }
                Text((controller.activeColor4LiveStyle ?: liveDjStyle).description,
                    color = Color(0xFF98A6C0), fontSize = 11.sp)
                if (controller.color4LiveDjRunning) {
                    Button(
                        onClick = controller::stopColor4LiveDj,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Live-DJ STOPPEN · Music-Modus sicher beenden") }
                } else {
                    Button(
                        onClick = { showLiveDjConfirm = true },
                        enabled = audioRunning && liveDjRefusal == null && !controller.busy,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Live-DJ auf ausgewählten Color4 starten") }
                }
                Text(
                    (liveDjRefusal ?: "Keine Zeitgrenze: eine bis vier bestätigte Color4 " +
                        "mit eigenen Music-Rückkanälen und gemeinsamem Beat. " +
                        "Läuft auch bei Displaysperre über den Vordergrunddienst. " +
                        "Nur echte Beat-Ereignisse, " +
                        "gemessene Akzente und gekennzeichnete BPM-Zwischentakte " +
                        "über individuell bestätigte Music-Sockets, niemals normales LAN-RGB."),
                    color = Color(0xFFAAB6CE), fontSize = 11.sp
                )
                if (showLiveDjConfirm) {
                    AlertDialog(
                        onDismissRequest = { showLiveDjConfirm = false },
                        title = { Text("Ausgewählte Color4 im Live-DJ starten?") },
                        text = { Text(
                            "Lichtstil: ${liveDjStyle.label}. " +
                                "Dynamisch nutzt erstmals sanfte Music-Übergänge und Helligkeit " +
                                "(noch separat am Gerät zu prüfen). " +
                                "Neue Funktion, noch nicht als Langzeitshow auf dieser Firmware " +
                                "abgenommen. Nur 1–4 intakte, bereits eingeschaltete Color4 " +
                                "mit bestätigtem Music-Modus; keine parallele Classic-Steuerung. " +
                                "Jede Lampe erhält einen eigenen set_music-Rückkanal. " +
                                "Stoppen hier oder (wenn erlaubt) über die Benachrichtigung. " +
                                "Bei Quota/ungeklärtem music_on kein Neustart. " +
                                "Vorherige Farben werden nicht automatisch zurückgesetzt."
                        ) },
                        confirmButton = {
                            TextButton(onClick = {
                                showLiveDjConfirm = false
                                if (android.os.Build.VERSION.SDK_INT >= 33 &&
                                    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                                    PackageManager.PERMISSION_GRANTED) {
                                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    controller.startForegroundColor4LiveDjGroupAware(liveDjStyle)
                                }
                            }) { Text("Live-DJ ausdrücklich starten") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showLiveDjConfirm = false }) {
                                Text("Abbrechen")
                            }
                        }
                    )
                }
                OutlinedButton(
                    onClick = { showMinimalProbeConfirm = true },
                    enabled = audioRunning && probeRefusal == null &&
                        !controller.minimalProbeRunning,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Color4-Transporttest · 8 Sekunden · Music-Rückkanal") }
                Text(
                    probeRefusal ?: "Referenz: max. acht Music-RGB-Frames/8 s, kein normales LAN-RGB. " +
                        "Lampe vorher einschalten; alte Farbe wird nicht automatisch wiederhergestellt.",
                    color = Color(0xFF98A6C0), fontSize = 11.sp
                )
                OutlinedButton(
                    onClick = { showTempoProbeConfirm = true },
                    enabled = audioRunning && probeRefusal == null &&
                        !controller.minimalProbeRunning,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Color4-Tempo-Test · 8 Sekunden · max. 16 Music-RGB") }
                Text(
                    "Nur einzeln und ausdrücklich: Beat-Ereignisse auf dem bestätigten " +
                        "Music-Rückkanal mit ≥450 ms Abstand. Wenn kein Bass-Beat erkannt wird, " +
                        "nur gemessene Audioaktivität mit ≥1 s Abstand. " +
                        "Keine zusätzlichen normalen LAN-RGB-Befehle.",
                    color = Color(0xFF98A6C0), fontSize = 11.sp
                )
                if (controller.minimalProbeNeedsReadback()) {
                    OutlinedButton(
                        onClick = controller::verifyMinimalProbeEnd,
                        enabled = !controller.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Music-Ende nur lesend nachprüfen") }
                }
                Text(controller.minimalProbeReport,
                    color = Color(0xFF98A6C0), fontSize = 11.sp)
                OutlinedButton(
                    onClick = {
                        val classicPackage = "com.yeelight.cherry"
                        val launch = context.packageManager.getLaunchIntentForPackage(classicPackage)
                        if (launch == null) {
                            controller.report("Yeelight Classic nicht installiert/auffindbar. " +
                                "Original-App unverändert belassen.")
                        } else {
                            try {
                                controller.stopMicrophone()
                                launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(launch)
                            } catch (failure: Exception) {
                                controller.report("Yeelight Classic konnte nicht gestartet werden: " +
                                    (failure.message ?: failure.javaClass.simpleName))
                            }
                        }
                    },
                    enabled = !controller.minimalProbeRunning && !controller.busy &&
                        !controller.musicLightArmed && !controller.scannerRunning &&
                        controller.runningDeviceIps.isEmpty() && !controller.recoveryPending,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Yeelight Classic öffnen · Räume, Farben, Szenen") }
                Text(
                    "Getrennte Original-App: kein Zugriff auf Classic-Zugangsdaten " +
                        "oder Cloud-Konto. Loft DJ beendet vor dem Wechsel sein Mikrofon.",
                    color = Color(0xFF98A6C0), fontSize = 11.sp
                )
                if (showMinimalProbeConfirm) {
                    AlertDialog(
                        onDismissRequest = { showMinimalProbeConfirm = false },
                        title = { Text("Eine Color4 einmalig prüfen?") },
                        text = {
                            Text("Nur mit eingeschalteter, zuvor funktionierender Color4. " +
                                "Die App liest einen Zustand, startet einen einzelnen " +
                                "set_music-Rückkanal, sendet höchstens acht Musikfarben " +
                                "innerhalb von acht Sekunden und schließt den Rückkanal. " +
                                "Kein normaler RGB-Fallback und kein automatischer Neustart. " +
                                "Farbzustand eventuell hinterher in Yeelight Classic ändern. " +
                                "Sobald Quota oder ein unbestätigter Stop auftritt, nicht wiederholen.")
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                showMinimalProbeConfirm = false
                                controller.runMinimalColor4Probe()
                            }) { Text("Einmalig testen") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showMinimalProbeConfirm = false }) {
                                Text("Abbrechen")
                            }
                        }
                    )
                }

                if (showTempoProbeConfirm) {
                    AlertDialog(
                        onDismissRequest = { showTempoProbeConfirm = false },
                        title = { Text("Schnelleren Color4-Music-Test zulassen?") },
                        text = {
                            Text("Dies ist ein neuer, noch nicht hardwarevalidierter Test. " +
                                "Eine gesunde eingeschaltete Color4, kein anderer Controller. " +
                                "Gleiches Music-Handshake-/Stop-/Readback wie beim Referenztest. " +
                                "Höchstens 16 RGB-Telegramme in acht Sekunden, ausschließlich " +
                                "über den Music-Socket, Mindestabstand 450 ms für echte Beats. " +
                                "Keine Wiederholung nach Fehler oder ungeklärtem Music-Ende. " +
                                "Nachher Yeelight Classic prüfen.")
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                showTempoProbeConfirm = false
                                controller.runMinimalColor4Probe(tempoTrial = true)
                            }) { Text("Einmaligen Tempo-Test starten") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showTempoProbeConfirm = false }) {
                                Text("Abbrechen")
                            }
                        }
                    )
                }

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
                    if (unboundedRefusal != null &&
                        controller.color4DualAcceptanceRefusal() != null) {
                        Text(unboundedRefusal,
                            color = Color(0xFF98A6C0), fontSize = 11.sp)
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
                OutlinedButton(
                    onClick = { dualDialogSelectionKey = controller.color4PairSelectionKey() },
                    enabled = audioRunning && !controller.busy &&
                        !controller.musicLightArmed &&
                        controller.runningDeviceIps.isEmpty() && dualReadiness == null,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Couchtisch + Flur · 10 Sekunden testen") }
                if (dualReadiness != null)
                    Text(dualReadiness, color = Color(0xFF98A6C0), fontSize = 11.sp)
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
                Text("Zustand sichern · automatisch stoppen · vorheriges Licht wiederherstellen.",
                    color = Color(0xFF98A6C0), fontSize = 11.sp)
            }
        }

        if (dualDialogSelectionKey != null) {
            AlertDialog(
                onDismissRequest = { dualDialogSelectionKey = null },
                title = { Text("Zwei-Lampen-Lichttest") },
                text = { Text(
                    "Couchtisch + Flur · 10 Sekunden · maximal 40 Farbtelegramme " +
                        "zusammen. Automatischer Stopp und Wiederherstellung."
                ) },
                confirmButton = {
                    TextButton(onClick = {
                        val approvedKey = dualDialogSelectionKey
                        dualDialogSelectionKey = null
                        // Never execute a different target than the pair
                        // explicitly shown in the user's confirmation.
                        if (approvedKey != null &&
                            approvedKey == controller.color4PairSelectionKey() &&
                            controller.color4DualAcceptanceRefusal() == null)
                            controller.armColor4DualAcceptance()
                        else controller.report("Auswahl geändert: bitte erneut bestätigen.")
                    }) { Text("10-Sekunden-Test starten") }
                },
                dismissButton = {
                    TextButton(onClick = { dualDialogSelectionKey = null }) {
                        Text("Abbrechen")
                    }
                }
            )
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
