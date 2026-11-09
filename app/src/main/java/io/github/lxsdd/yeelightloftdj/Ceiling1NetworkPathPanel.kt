package io.github.lxsdd.yeelightloftdj

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
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
 * Purely manual and volatile ceiling1 incident triage: zero device commands.
 * No WLAN/BLE/Cloud/FRITZ!Box permissions, no log upload and no app persistence.
 */
@Composable
internal fun Ceiling1NetworkPathPanel() {
    var ap by remember { mutableStateOf(Ceiling1ApState.UNKNOWN) }
    var manufacturerApp by remember { mutableStateOf(Ceiling1AppState.UNKNOWN) }
    var lan by remember { mutableStateOf(Ceiling1LanState.UNKNOWN) }
    var ble by remember { mutableStateOf(Ceiling1BleState.UNKNOWN) }
    var simultaneous by remember { mutableStateOf(false) }

    val finding = remember(ap, manufacturerApp, lan, ble, simultaneous) {
        Ceiling1NetworkPathDiagnostic.evaluate(Ceiling1NetworkEvidence(
            ap=ap,
            app=manufacturerApp,
            lan=lan,
            ble=ble,
            timing=if (simultaneous) Ceiling1DiagnosticTiming.CONTEMPORANEOUS
                else Ceiling1DiagnosticTiming.UNKNOWN,
        ))
    }

    Surface(color=Color(0xFF252B3D), shape=RoundedCornerShape(16.dp)) {
        Column(verticalArrangement=Arrangement.spacedBy(8.dp),
            modifier=Modifier.fillMaxWidth()) {
            Text("ceiling1 · WLAN-/Cloud-Pfad unterscheiden",
                color=Color.White, fontSize=15.sp)
            Text("Nur Beobachtungen der gleichen Lampe und des gleichen Zeitraums. " +
                "In FRITZ!Box WLAN → Funknetz muss die Lampe aktuell verbunden sein; " +
                "die Liste bekannter Geräte oder eine DHCP-Lease zählt nicht.",
                color=Color(0xFFC2C9D8), fontSize=12.sp)
            Text("1 · Ist die Lampe JETZT mit dem WLAN verbunden?",
                color=Color.White, fontSize=12.sp)
            @Composable fun <T> options(current:T, items:List<Pair<T,String>>, select:(T)->Unit) {
                Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    items.forEach { (item, label) ->
                        FilterChip(
                            selected=current==item,
                            onClick={ select(item);simultaneous=false },
                            label={ Text(label,fontSize=10.sp) }
                        )
                    }
                }
            }
            options(ap,listOf(
                Ceiling1ApState.UNKNOWN to "Unbekannt",
                Ceiling1ApState.ASSOCIATED_NOW to "Verbunden",
                Ceiling1ApState.NOT_ASSOCIATED_NOW to "Getrennt"
            )) { ap=it }

            Text("2 · Funktioniert die Yeelight-App in diesem Moment?",
                color=Color.White, fontSize=12.sp)
            options(manufacturerApp,listOf(
                Ceiling1AppState.UNKNOWN to "Unbekannt",
                Ceiling1AppState.CURRENT_CONTROL_WORKS to "Ja",
                Ceiling1AppState.CURRENT_CONTROL_FAILS to "Nein"
            )) { manufacturerApp=it }

            Text("3 · Lokaler LAN-Dienst: bereits unabhängig bestätigt?",
                color=Color.White, fontSize=12.sp)
            options(lan,listOf(
                Ceiling1LanState.UNKNOWN to "Nicht geprüft",
                Ceiling1LanState.CURRENT_LAN_CONFIRMED to "Erreichbar",
                Ceiling1LanState.CURRENT_LAN_FAILS to "Nicht erreichbar"
            )) { lan=it }
            Text("Keinen neuen LAN-Test auslösen! Nur vorhandene Belege verwenden.",
                color=Color(0xFFD7BFA8),fontSize=11.sp)

            Text("4 · Original-BLE-Fernbedienung: Bedienung möglich?",
                color=Color.White, fontSize=12.sp)
            options(ble,listOf(
                Ceiling1BleState.UNKNOWN to "Unbekannt",
                Ceiling1BleState.CURRENT_BLE_WORKS to "Ja",
                Ceiling1BleState.CURRENT_BLE_FAILS to "Nein"
            )) { ble=it }

            Row(horizontalArrangement=Arrangement.spacedBy(7.dp)) {
                Checkbox(
                    checked=simultaneous,
                    enabled=ap!=Ceiling1ApState.UNKNOWN,
                    onCheckedChange={ simultaneous=it },
                )
                Text("Angaben stammen aus demselben Beobachtungsmoment",
                    color=Color(0xFFC2C9D8),fontSize=11.sp)
            }
            Text(finding.explanation,color=Color(0xFF80D9C6),fontSize=12.sp)
            Text("WLAN-Ausfall ist NICHT Color4-Quota. WLAN-Verbunden ist " +
                "NICHT Cloud-Online. BLE-Online ist NICHT WLAN-Online. " +
                "Kein automatisches Scannen, Reset, Power-Cycle oder Firmwareflash.",
                color=Color(0xFFC2C9D8),fontSize=11.sp)
        }
    }
}
