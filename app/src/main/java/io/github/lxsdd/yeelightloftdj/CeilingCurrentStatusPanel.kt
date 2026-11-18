package io.github.lxsdd.yeelightloftdj

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
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
 * Passive manual triage: users choose observations, no FRITZ!Box API,
 * sockets, discovery, Cloud/BLE permissions, logging or persistence.
 */
@Composable
internal fun CeilingCurrentStatusPanel() {
    var apNow by remember { mutableStateOf(ApAssociationNow.UNKNOWN) }
    var appNow by remember { mutableStateOf(YeelightControlNow.UNKNOWN) }
    var sameMoment by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text("Aktueller Zustand – unabhängig vom alten Ereignisprotokoll",
            color = Color.White, fontSize = 14.sp)
        Text(
            "Eine frühere WLAN-Anmeldung und eine nicht mehr sichtbare Abmeldung " +
                "beweisen keine bestehende Verbindung. Die Liste bekannter Geräte " +
                "ist nicht gleich der Liste aktuell verbundener WLAN-Geräte.",
            fontSize = 12.sp, color = Color(0xFFC2C9D8),
        )
        Text("Jetzt unter WLAN → Funknetz: Ist genau diese Lampe aktuell verbunden?",
            fontSize = 12.sp, color = Color(0xFFC2C9D8))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for ((choice, label) in listOf(
                ApAssociationNow.UNKNOWN to "Ungeprüft",
                ApAssociationNow.PRESENT to "Verbunden",
                ApAssociationNow.ABSENT to "Nicht verbunden",
            )) {
                OutlinedButton(
                    onClick = { apNow = choice; sameMoment = false },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (apNow == choice) "✓ $label" else label, fontSize = 10.sp)
                }
            }
        }
        Text("Zur gleichen Zeit: Funktioniert die Bedienung in der Yeelight-App?",
            fontSize = 12.sp, color = Color(0xFFC2C9D8))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for ((choice, label) in listOf(
                YeelightControlNow.UNKNOWN to "Ungeprüft",
                YeelightControlNow.WORKS to "Funktioniert",
                YeelightControlNow.FAILS to "Offline",
            )) {
                OutlinedButton(
                    onClick = { appNow = choice; sameMoment = false },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (appNow == choice) "✓ $label" else label, fontSize = 10.sp)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Checkbox(
                checked = sameMoment,
                onCheckedChange = { sameMoment = it },
                enabled = apNow != ApAssociationNow.UNKNOWN,
            )
            Text("Beobachtungen stammen aus demselben aktuellen Zeitraum",
                fontSize = 11.sp, color = Color(0xFFC2C9D8))
        }
        val assessment = remember(apNow, appNow, sameMoment) {
            CeilingCurrentDiagnostic.evaluate(
                CeilingCurrentEvidence(
                    // No historical log entry is used as a current-state proof.
                    association = apNow,
                    yeelightControl = appNow,
                    timing = if (sameMoment) ObservationTiming.SAME_MOMENT
                        else ObservationTiming.UNKNOWN,
                )
            )
        }
        Text(assessment.note, fontSize = 12.sp, color = Color(0xFF80D9C6))
        Text("Keine automatischen Tests und keine Router-/Firmwareänderungen. " +
            "Die Bestätigung einer WLAN-Anmeldung ist keine Bestätigung " +
            "für den lokalen Yeelight-Dienst oder die Cloud.",
            fontSize = 11.sp, color = Color(0xFFC2C9D8))
    }
}
