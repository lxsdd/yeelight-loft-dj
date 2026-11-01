package io.github.lxsdd.yeelightloftdj

/**
 * Independent, passive classification of a ceiling1 Wi-Fi/Cloud incident.
 * This is NOT a color4 LAN-quota classifier. No probes, BLE, Wi-Fi, cloud,
 * router permissions, telemetry, persistence, retries or power operations.
 */
enum class Ceiling1ApState { UNKNOWN, ASSOCIATED_NOW, NOT_ASSOCIATED_NOW }
enum class Ceiling1AppState { UNKNOWN, CURRENT_CONTROL_WORKS, CURRENT_CONTROL_FAILS }
enum class Ceiling1LanState { UNKNOWN, CURRENT_LAN_CONFIRMED, CURRENT_LAN_FAILS }
enum class Ceiling1BleState { UNKNOWN, CURRENT_BLE_WORKS, CURRENT_BLE_FAILS }
enum class Ceiling1DiagnosticTiming { UNKNOWN, CONTEMPORANEOUS, STALE_OR_DIFFERENT_TIMES }

data class Ceiling1NetworkEvidence(
    val ap: Ceiling1ApState = Ceiling1ApState.UNKNOWN,
    val app: Ceiling1AppState = Ceiling1AppState.UNKNOWN,
    val lan: Ceiling1LanState = Ceiling1LanState.UNKNOWN,
    val ble: Ceiling1BleState = Ceiling1BleState.UNKNOWN,
    val timing: Ceiling1DiagnosticTiming = Ceiling1DiagnosticTiming.UNKNOWN,
    /** Historical events NEVER prove a current WLAN connection. */
    val historicAssociationLogPresent: Boolean = false,
    val historicDisconnectLogPresent: Boolean = false,
    /** DHCP lease may remain cached even when station has disconnected. */
    val dhcpLeaseExists: Boolean = false,
)

enum class Ceiling1NetworkFinding {
    NEED_CURRENT_AP_EVIDENCE,
    OBSERVATIONS_NOT_SIMULTANEOUS,
    OBSERVATIONS_CONTRADICT,
    WLAN_ASSOCIATION_LOST_BLE_ALIVE,
    WLAN_ASSOCIATION_LOST_BLE_UNVERIFIED,
    WLAN_ASSOCIATED_LOCAL_PATH_DOWN,
    WLAN_ASSOCIATED_APP_CLOUD_PATH_DOWN,
    WLAN_ASSOCIATED_BOTH_PATHS_UNAVAILABLE,
    WLAN_ASSOCIATED_APP_PATH_UNRESOLVED,
    WLAN_ASSOCIATED_HEALTHY_OBSERVED,
    WLAN_ASSOCIATED_CLOUD_ONLY_NO_LAN_PROOF,
    WLAN_ASSOCIATED_PATHS_NOT_CHECKED,
}

data class Ceiling1NetworkAssessment(
    val finding: Ceiling1NetworkFinding,
    val explanation: String,
    val wlanDisassociationSupported: Boolean = false,
    val firmwareFailureProven: Boolean = false,
    val color4QuotaDiagnosisApplies: Boolean = false,
    val automaticRecoveryPermitted: Boolean = false,
)

object Ceiling1NetworkPathDiagnostic {
    fun evaluate(e: Ceiling1NetworkEvidence): Ceiling1NetworkAssessment {
        // AP current state must be from the currently connected WLAN clients,
        // never "known devices", DHCP lease nor old router event timestamps.
        if (e.ap == Ceiling1ApState.UNKNOWN) return Ceiling1NetworkAssessment(
            Ceiling1NetworkFinding.NEED_CURRENT_AP_EVIDENCE,
            "WLAN-Verbindung unbekannt. Eine alte Anmeldung oder DHCP-Lease " +
                "ist kein aktueller Verbindungsnachweis.",
        )
        if (e.timing != Ceiling1DiagnosticTiming.CONTEMPORANEOUS) return Ceiling1NetworkAssessment(
            Ceiling1NetworkFinding.OBSERVATIONS_NOT_SIMULTANEOUS,
            "Beobachtungen sind nicht zeitgleich. WLAN, Cloud und BLE " +
                "dürfen nicht aus verschiedenen Zeitpunkten kombiniert werden.",
        )
        if (e.ap == Ceiling1ApState.NOT_ASSOCIATED_NOW) {
            if (e.lan == Ceiling1LanState.CURRENT_LAN_CONFIRMED ||
                e.app == Ceiling1AppState.CURRENT_CONTROL_WORKS) {
                return Ceiling1NetworkAssessment(
                    Ceiling1NetworkFinding.OBSERVATIONS_CONTRADICT,
                    "Aktuell fehlende WLAN-Anmeldung widerspricht bestätigter " +
                        "Geräteantwort. Gerätezuordnung und Zeitpunkt prüfen.",
                )
            }
            val bleAlive = e.ble == Ceiling1BleState.CURRENT_BLE_WORKS
            return Ceiling1NetworkAssessment(
                if (bleAlive) Ceiling1NetworkFinding.WLAN_ASSOCIATION_LOST_BLE_ALIVE
                else Ceiling1NetworkFinding.WLAN_ASSOCIATION_LOST_BLE_UNVERIFIED,
                if (bleAlive)
                    "WLAN aktuell nicht assoziiert, BLE reagiert. " +
                        "WLAN-/Reassoziations- oder Funkmodulzustand eingrenzen. " +
                        "BLE-Erfolg ist kein WLAN-/Firmwarebeweis."
                else
                    "Lampe aktuell nicht am WLAN angemeldet. WLAN-/AP-Ereignisse " +
                        "prüfen; BLE und interne Firmware-Uptime sind ungeklärt.",
                wlanDisassociationSupported = true,
            )
        }
        // A contemporaneous manufacturer app response may travel by cloud.
        if (e.lan == Ceiling1LanState.CURRENT_LAN_CONFIRMED &&
            e.app == Ceiling1AppState.CURRENT_CONTROL_FAILS)
            return Ceiling1NetworkAssessment(
                Ceiling1NetworkFinding.WLAN_ASSOCIATED_APP_CLOUD_PATH_DOWN,
                "Lokaler LAN-Dienst bestätigt, Hersteller-App funktioniert nicht: " +
                    "App-/Cloud-/Sitzungspfad separat untersuchen.",
            )
        if (e.lan == Ceiling1LanState.CURRENT_LAN_FAILS &&
            e.app == Ceiling1AppState.CURRENT_CONTROL_WORKS)
            return Ceiling1NetworkAssessment(
                Ceiling1NetworkFinding.WLAN_ASSOCIATED_LOCAL_PATH_DOWN,
                "WLAN und Hersteller-App funktionieren, lokaler Dienst nicht. " +
                    "App-/Cloud-Erfolg heilt nicht automatisch den LAN-Dienst.",
            )
        if (e.lan == Ceiling1LanState.CURRENT_LAN_FAILS &&
            e.app == Ceiling1AppState.CURRENT_CONTROL_FAILS)
            return Ceiling1NetworkAssessment(
                Ceiling1NetworkFinding.WLAN_ASSOCIATED_BOTH_PATHS_UNAVAILABLE,
                "WLAN verbunden, aber lokaler und Hersteller-App-Pfad ohne Antwort. " +
                    "DHCP/IP, Netzwerk-Stack und Cloud-Sitzung getrennt betrachten.",
            )
        if (e.lan == Ceiling1LanState.CURRENT_LAN_CONFIRMED &&
            e.app == Ceiling1AppState.CURRENT_CONTROL_WORKS)
            return Ceiling1NetworkAssessment(
                Ceiling1NetworkFinding.WLAN_ASSOCIATED_HEALTHY_OBSERVED,
                "WLAN, lokaler Dienst und Hersteller-App sind derzeit erreichbar. " +
                    "Ein früherer Offline-Zustand ist dadurch nicht erklärt.",
            )
        if (e.app == Ceiling1AppState.CURRENT_CONTROL_WORKS)
            return Ceiling1NetworkAssessment(
                Ceiling1NetworkFinding.WLAN_ASSOCIATED_CLOUD_ONLY_NO_LAN_PROOF,
                "WLAN und Hersteller-App funktionieren; der native LAN-Pfad " +
                    "wurde nicht bestätigt. Google Home ist ebenfalls kein LAN-Beweis.",
            )
        if (e.app == Ceiling1AppState.CURRENT_CONTROL_FAILS)
            return Ceiling1NetworkAssessment(
                Ceiling1NetworkFinding.WLAN_ASSOCIATED_APP_PATH_UNRESOLVED,
                "WLAN derzeit verbunden, Hersteller-App offline. Lokaler " +
                    "Dienst oder Cloud-Ursache noch nicht unterschieden.",
            )
        return Ceiling1NetworkAssessment(
            Ceiling1NetworkFinding.WLAN_ASSOCIATED_PATHS_NOT_CHECKED,
            "Aktuelle WLAN-Anmeldung vorhanden; lokaler Dienst und " +
                "Cloud-/Hersteller-App-Pfad unbekannt.",
        )
    }
}
