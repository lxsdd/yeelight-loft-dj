package io.github.lxsdd.yeelightloftdj

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Volatile, opt-in manual research context, not an automatic LAN test.
 * The known two quota reports are anonymized; all other lamps are UNKNOWN
 * until the user has independent, prior evidence of successful LAN commands.
 */
@Composable
internal fun Color4CohortResearchPanel() {
    var peerFirmwareConfirmed by remember { mutableStateOf(false) }
    var peerLanAckConfirmed by remember { mutableStateOf(false) }

    val report = remember(peerFirmwareConfirmed, peerLanAckConfirmed) {
        val known = (1..2).map { i ->
            Color4CohortEntry(
                slot = i,
                firmware = "2.1.7_0039",
                runtime = Color4RuntimeEvidence.WALL_POWER_OVER_50_DAYS_ONLY,
                lanOutcome = Color4LanOutcome.EXPLICIT_CLIENT_QUOTA_EXCEEDED,
            )
        }
        val other = Color4CohortEntry(
            slot = 3,
            firmware = if (peerFirmwareConfirmed) "2.1.7_0039" else null,
            runtime = Color4RuntimeEvidence.WALL_POWER_OVER_50_DAYS_ONLY,
            lanOutcome = if (peerLanAckConfirmed) Color4LanOutcome.LAN_COMMAND_ACKNOWLEDGED
                else Color4LanOutcome.NOT_OBSERVED,
        )
        Color4CohortAnalysis.analyze(known + other)
    }
    Surface(color = Color(0xFF252B3D), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("Color4 · 50-Tage-Vergleich", color = Color.White)
            Text(
                "Bekannt: Zwei color4 mit Firmware 2.1.7_0039 meldeten " +
                    "'client quota exceeded'. Auch weitere Lampen waren " +
                    "länger als 50 Tage mit Netzstrom versorgt. " +
                    "Netzstromdauer ist keine gemessene Firmware-Uptime.",
                fontSize = 12.sp, color = Color(0xFFC2C9D8),
            )
            Text(
                "Andere Lampen ohne dokumentierten Quota-Fehler sind " +
                    "nicht automatisch bestätigte LAN-Kontrollgeräte. " +
                    "Google Home oder Hersteller-App belegen keinen TCP-55443-Erfolg.",
                fontSize = 12.sp, color = Color(0xFFC2C9D8),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Checkbox(checked = peerFirmwareConfirmed,
                    onCheckedChange = { peerFirmwareConfirmed = it })
                Text("Bei einer anderen Lampe ist exakt 2.1.7_0039 bestätigt",
                    fontSize = 12.sp, color = Color(0xFFC2C9D8))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Checkbox(checked = peerLanAckConfirmed,
                    onCheckedChange = { peerLanAckConfirmed = it })
                Text("Dort wurde nach 50 Tagen tatsächlich eine direkte " +
                    "LAN-Antwort bestätigt (nicht nur Cloud/App)",
                    fontSize = 12.sp, color = Color(0xFFC2C9D8))
            }
            Text(report.safeNextStep,
                fontSize = 12.sp, color = Color(0xFF80D9C6))
            Text(
                "Reine lokale Modellrechnung. Kein Verbindungsaufbau, " +
                    "kein Firmware-Update, keine Speicherung der Eingaben.",
                fontSize = 11.sp, color = Color(0xFFC2C9D8),
            )
        }
    }
}
