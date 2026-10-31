package io.github.lxsdd.yeelightloftdj

import android.Manifest
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
    val reading = controller.musicFrame

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Musik im Loft", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(
            "Echte Audioanalyse über das Smartphone-Mikrofon. Für DJ-Sets, Lautsprecher " +
                "und Musik am Google-Cast-Empfänger.",
            color = Color(0xFFAAB6CE), fontSize = 13.sp
        )
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
                if (reading != null && controller.microphoneRunning) {
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
                            "Höre auf deutliche Bass-Impulse · BPM nur bei stabilen Ereignissen",
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
                        enabled = controller.microphoneRunning && !controller.busy &&
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
                "Weitere Audioquellen bleiben geplant: internes Android-Audio nur mit " +
                    "Einwilligung und Freigabe der Quell-App; lokaler Player. Bei Google Cast " +
                    "wird die Musik meist auf dem Empfänger abgespielt – deshalb ist das " +
                    "Mikrofon hier die verlässliche Einstiegslösung.",
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
