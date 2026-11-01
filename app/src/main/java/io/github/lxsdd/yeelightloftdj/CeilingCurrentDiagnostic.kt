package io.github.lxsdd.yeelightloftdj

/**
 * Manual, simultaneous observations only; completely independent of LAN,
 * BLE, discovery and FRITZ!Box connectors. Historical event logs may be
 * incomplete and MUST NOT infer the current association state.
 */
enum class ApAssociationNow { UNKNOWN, PRESENT, ABSENT }
enum class YeelightControlNow { UNKNOWN, WORKS, FAILS }
enum class LocalServiceNow { UNKNOWN, REACHABLE, UNREACHABLE, QUOTA_REJECTED }
enum class ObservationTiming { UNKNOWN, SAME_MOMENT, DIFFERENT_TIMES }

data class CeilingCurrentEvidence(
    val recentRegistrationInLog: Boolean = false,
    val disconnectStillVisibleInLog: Boolean = false,
    val eventHistoryComplete: Boolean = false,
    val association: ApAssociationNow = ApAssociationNow.UNKNOWN,
    val yeelightControl: YeelightControlNow = YeelightControlNow.UNKNOWN,
    val lanService: LocalServiceNow = LocalServiceNow.UNKNOWN,
    val timing: ObservationTiming = ObservationTiming.UNKNOWN,
)

enum class CeilingCurrentFinding {
    CURRENT_WLAN_UNKNOWN,
    CURRENT_WLAN_ABSENT,
    WLAN_PRESENT_APP_WORKS,
    WLAN_PRESENT_APP_FAILS_PATH_UNKNOWN,
    WLAN_PRESENT_LOCAL_SERVICE_UNREACHABLE,
    WLAN_PRESENT_APP_FAILS_BUT_LOCAL_SERVICE_REACHABLE,
    WLAN_PRESENT_LOCAL_SERVICE_REACHABLE,
    WLAN_PRESENT_SERVICE_UNVERIFIED,
    LAN_QUOTA_STOP,
    UNSYNCHRONIZED_EVIDENCE,
    CONTRADICTORY_OBSERVATIONS,
}

data class CeilingCurrentAssessment(
    val finding: CeilingCurrentFinding,
    val note: String,
    val currentWifiAbsenceSupported: Boolean = false,
    val firmwareFailureProven: Boolean = false,
)

/**
 * Explicitly fails open to UNKNOWN, never silently treats the absence of a
 * historic disconnect message as evidence of uninterrupted WLAN service.
 */
object CeilingCurrentDiagnostic {
    fun evaluate(e: CeilingCurrentEvidence): CeilingCurrentAssessment {
        // Periodic or missing router event messages can never set association,
        // even with proof that event logging is enabled.
        if (e.association == ApAssociationNow.UNKNOWN) return CeilingCurrentAssessment(
            CeilingCurrentFinding.CURRENT_WLAN_UNKNOWN,
            "Die letzte WLAN-Anmeldung ist nur historisch. Bitte den aktuellen " +
                "WLAN-Verbindungsstatus der Lampe prüfen; eine fehlende Abmeldung " +
                "im sichtbaren Protokoll beweist keine bestehende Verbindung.",
        )
        if (e.timing != ObservationTiming.SAME_MOMENT) return CeilingCurrentAssessment(
            CeilingCurrentFinding.UNSYNCHRONIZED_EVIDENCE,
            "Beobachtungen stammen nicht nachweisbar aus demselben Zeitraum. " +
                "Keine gemeinsame Fehlerursache ableiten.",
        )
        if (e.association == ApAssociationNow.ABSENT) {
            if (e.lanService == LocalServiceNow.REACHABLE)
                return CeilingCurrentAssessment(
                    CeilingCurrentFinding.CONTRADICTORY_OBSERVATIONS,
                    "Router-Anzeige und lokale Erreichbarkeit widersprechen sich. " +
                        "Zeitpunkt und Gerätezuordnung erneut prüfen.",
                )
            return CeilingCurrentAssessment(
                CeilingCurrentFinding.CURRENT_WLAN_ABSENT,
                "Aktuelle WLAN-Zuordnung fehlt. Zuerst Funkverbindung, " +
                    "AP/Repeater und Router-Ereignisse prüfen; keine LAN-Retry-Flut.",
                currentWifiAbsenceSupported = true,
            )
        }

        if (e.lanService == LocalServiceNow.QUOTA_REJECTED) return CeilingCurrentAssessment(
            CeilingCurrentFinding.LAN_QUOTA_STOP,
            "Die Firmware hat ein LAN-Limit gemeldet: keine weiteren " +
                "Diagnose- oder Steuerungsbefehle senden.",
        )
        if (e.lanService == LocalServiceNow.UNREACHABLE) return CeilingCurrentAssessment(
            CeilingCurrentFinding.WLAN_PRESENT_LOCAL_SERVICE_UNREACHABLE,
            "WLAN ist aktuell als verbunden eingetragen, aber der lokale " +
                "Dienst reagiert nicht. WLAN-Anmeldung und funktionierender " +
                "Gerätedienst sind verschiedene Zustände.",
        )
        if (e.yeelightControl == YeelightControlNow.WORKS) return CeilingCurrentAssessment(
            CeilingCurrentFinding.WLAN_PRESENT_APP_WORKS,
            "WLAN und Hersteller-App funktionieren im Beobachtungsmoment. " +
                "Das erklärt den früheren Ausfall noch nicht.",
        )
        if (e.yeelightControl == YeelightControlNow.FAILS) {
            if (e.lanService == LocalServiceNow.REACHABLE) return CeilingCurrentAssessment(
                CeilingCurrentFinding.WLAN_PRESENT_APP_FAILS_BUT_LOCAL_SERVICE_REACHABLE,
                "Lokaler Dienst erreichbar, Hersteller-App ohne Funktion: " +
                    "App-/Cloud-/Kontenpfad untersuchen, keinen Firmwaredefekt behaupten.",
            )
            return CeilingCurrentAssessment(
                CeilingCurrentFinding.WLAN_PRESENT_APP_FAILS_PATH_UNKNOWN,
                "WLAN aktuell verbunden, Hersteller-App ohne Funktion. " +
                    "Ob lokaler Dienst, Cloud oder App betroffen sind, ist ungeklärt.",
            )
        }
        return CeilingCurrentAssessment(
            if (e.lanService == LocalServiceNow.REACHABLE)
                CeilingCurrentFinding.WLAN_PRESENT_LOCAL_SERVICE_REACHABLE
            else CeilingCurrentFinding.WLAN_PRESENT_SERVICE_UNVERIFIED,
            if (e.lanService == LocalServiceNow.REACHABLE)
                "WLAN und lokaler Dienst sind erreichbar; App-/Cloudpfad unbekannt."
            else "WLAN aktuell verbunden, aber lokale und Cloud-Steuerung nicht geprüft.",
        )
    }
}
