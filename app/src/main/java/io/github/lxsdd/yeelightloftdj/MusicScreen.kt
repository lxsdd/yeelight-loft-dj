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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

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
                if (reading != null && audioRunning) {
                    Text("Live-Frequenzspektrum", color = Color.White, fontWeight = FontWeight.Medium)
                    reading.bands.forEachIndexed { index, value ->
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
                    HorizontalDivider(color = Color(0xFF323A50))
                    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        MusicMetric("Pegel", (reading.rms * 100).roundToInt().toString() + " %")
                        MusicMetric("Beats", controller.musicBeatCount.toString())
                        MusicMetric("BPM", reading.estimatedBpm?.toString() ?: "–")
                    }
                    Text(
                        if (reading.beat) "Bass-Transient erkannt" else
                            "Erkannte Audio-Transienten · BPM nur bei stabilen Ereignissen",
                        color = if (reading.beat) Color(0xFF67E1C8) else Color(0xFF98A6C0),
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
                Text("Sicherheitsgrenze: maximal ein RGB-Befehl alle 1,5 Sekunden je Lampe. " +
                    "Keine Stroboskop-Effekte, kein unkontrolliertes Dauerfeuer.",
                    color = Color(0xFFAAB6CE), fontSize = 11.sp)
                if (!controller.musicLightArmed && controller.runningDeviceIps.isEmpty()) {
                    Button(
                        onClick = controller::armMusicLights,
                        enabled = audioRunning && !controller.busy &&
                            controller.selectedCount() > 0 && controller.runningDeviceIps.isEmpty(),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Licht auf ${controller.selectedCount()} Lampen aktivieren") }
                } else {
                    OutlinedButton(
                        onClick = controller::disarmMusicLights,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !controller.busy
                    ) { Text("Musiklicht stoppen und vorheriges Licht wiederherstellen") }
                }
                Text(controller.musicLightStatus, color = Color(0xFF98A6C0), fontSize = 12.sp)
                Text("Vor Aktivierung werden alle ursprünglichen Lampenzustände lokal gesichert. " +
                    "Wenn diese nicht lesbar sind, wird kein Licht verändert.",
                    color = Color(0xFF98A6C0), fontSize = 11.sp)
            }
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
