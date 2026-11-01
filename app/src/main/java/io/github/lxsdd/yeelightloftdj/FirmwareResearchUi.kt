package io.github.lxsdd.yeelightloftdj

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Read-only device research card: uses cached SSDP methods; no probe or writes. */
@Composable
internal fun FirmwareResearchPanel(device: YeelightDevice) {
    val insight = remember(device.model, device.methods) {
        FirmwareInsightReport.summarize(device.model, device.methods)
    }
    Surface(color = Color(0xFF252B3D), shape = RoundedCornerShape(16.dp)) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text("Firmware & Chroma · Forschungsstand", color = Color.White,
                fontWeight = FontWeight.SemiBold)
            Text(insight.evidence, fontSize = 12.sp, color = Color(0xFFC2C9D8))
            Text(insight.chroma, fontSize = 12.sp, color = Color(0xFF80D9C6))
            Text(insight.firmware, fontSize = 12.sp, color = Color(0xFFC2C9D8))
            Text("Keine Firmware-, UDP- oder Flash-Freigabe. Keine zusätzlichen " +
                "Lampenbefehle durch diese Anzeige.",
                fontSize = 11.sp, color = Color(0xFFD7BFA8))
        }
    }
}

/**
 * Local FRITZ!Box incident research tool.
 * User types ONLY detached timestamps, never full event lines, device names,
 * MACs or addresses. Input stays in this composable's volatile memory.
 */
@Composable
internal fun Ceiling1EventsResearchPanel() {
    var expanded by remember { mutableStateOf(false) }
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }

    Surface(color = Color(0xFF252B3D), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("ceiling1 · WLAN-Ausfälle ohne Lampenabfragen", color = Color.White,
                fontWeight = FontWeight.SemiBold)
            Text("Vergleiche bereits vorhandene FRITZ!Box-WLAN-Abmeldezeiten. " +
                "Das Werkzeug nutzt ausschließlich manuell eingegebene Zeitpunkte.",
                fontSize = 12.sp, color = Color(0xFFC2C9D8))
            OutlinedButton(onClick = { expanded = !expanded },
                modifier = Modifier.fillMaxWidth()) {
                Text(if (expanded) "Zeitanalyse schließen" else "Zeitanalyse öffnen")
            }
            if (expanded) {
                CeilingCurrentStatusPanel()
                Text("FRITZ!Box: System → Ereignisse → WLAN. Gegebenenfalls zuerst " +
                    "die Protokollierung zusätzlicher WLAN-An-/Abmeldungen aktivieren. " +
                    "Nur wirkliche Abmeldungen der betreffenden Lampe zählen.",
                    fontSize = 12.sp, color = Color(0xFFC2C9D8))
                Text("Pro Zeile ausschließlich Datum und Uhrzeit, z. B. " +
                    "01.11.2026 10:00:00. Keine vollständigen Router-Logs, " +
                    "Namen, IP- oder MAC-Adressen eingeben.",
                    fontSize = 12.sp, color = Color(0xFFD7BFA8))
                OutlinedTextField(
                    value = first, onValueChange = { first = it.take(8193) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Lampe A · WLAN-Abmeldungen") },
                    minLines = 3, maxLines = 6,
                )
                val result = remember(first) { CeilingManualTimeline.analyze(first) }
                Text(manualCeilingResultLabel(result), fontSize = 12.sp,
                    color = Color(0xFF80D9C6))
                OutlinedTextField(
                    value = second, onValueChange = { second = it.take(8193) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Lampe B · optionaler Vergleich") },
                    minLines = 2, maxLines = 5,
                )
                if (second.isNotBlank()) {
                    val cross = remember(first, second) {
                        CeilingManualTimeline.compare(first, second)
                    }
                    val status = when (cross) {
                        CeilingComparison.UNKNOWN ->
                            "Vergleich noch nicht belastbar: pro Lampe mindestens 4 echte Abmeldungen"
                        CeilingComparison.SIMULTANEOUS ->
                            "Drei oder mehr Ausfälle liegen zeitlich nahe beieinander"
                        CeilingComparison.INDEPENDENT ->
                            "Keine ausreichende zeitliche Übereinstimmung"
                    }
                    Text(status, fontSize = 12.sp, color = Color(0xFFC2C9D8))
                }
                Text("Ein regelmäßiges Muster beweist keinen Firmwarefehler. " +
                    "Bei fehlenden Router-Ereignissen ist ein WLAN-Ausfall nicht belegt. " +
                    "Keine Übertragung, Speicherung oder automatische Netzwerkaktion.",
                    fontSize = 11.sp, color = Color(0xFFC2C9D8))
                TextButton(onClick = { first = ""; second = "" }) {
                    Text("Lokale Eingaben verwerfen")
                }
            }
        }
    }
}

private fun manualCeilingResultLabel(result: CeilingManualResult): String =
    when (result.status) {
        CeilingManualStatus.EMPTY -> "Noch keine Zeitpunkte erfasst"
        CeilingManualStatus.TOO_FEW ->
            "Nur " + result.count + " Abmeldungen; mindestens vier erforderlich"
        CeilingManualStatus.HOURLY_PATTERN ->
            "Hinweis: annähernd stündliches Muster bei " +
                result.count + " Abmeldungen, keine gesicherte Fehlerursache"
        CeilingManualStatus.IRREGULAR ->
            "Unregelmäßige Abstände bei " + result.count + " Abmeldungen"
        CeilingManualStatus.INVALID ->
            "Ungültige Zeile/Duplikat: nur Datums- und Zeitangaben eingeben"
        CeilingManualStatus.DST_AMBIGUOUS ->
            "Zeitumstellung: mindestens ein Zeitpunkt ist nicht eindeutig"
        CeilingManualStatus.TOO_LARGE -> "Zu viele oder zu lange Eingaben"
    }
