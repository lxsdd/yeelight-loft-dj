package io.github.lxsdd.yeelightloftdj

/** Yeelight WiFi Inter-Operation specification, table 4-1.
 * Device SUPPORT decides physical capability; the app implementation status
 * is deliberately separate. "Reported" must never mean "works in this app".
 */
enum class ImplementationStatus { AVAILABLE, PARTIAL, NOT_IMPLEMENTED }

data class ProtocolMethod(
    val id: String,
    val title: String,
    val group: String,
    val status: ImplementationStatus,
)

object YeelightCommandCatalog {
    val methods = listOf(
        ProtocolMethod("get_prop","Status abfragen","Basis", ImplementationStatus.AVAILABLE),
        ProtocolMethod("set_power","Ein/Aus","Basis", ImplementationStatus.AVAILABLE),
        ProtocolMethod("toggle","Ein/Aus umschalten","Basis", ImplementationStatus.NOT_IMPLEMENTED),
        ProtocolMethod("set_bright","Helligkeit","Basis", ImplementationStatus.AVAILABLE),
        ProtocolMethod("set_ct_abx","Weißtemperatur","Basis", ImplementationStatus.AVAILABLE),
        ProtocolMethod("set_rgb","RGB-Farbe","Basis", ImplementationStatus.AVAILABLE),
        ProtocolMethod("set_hsv","HSV-Farbwahl","Basis", ImplementationStatus.AVAILABLE),
        ProtocolMethod("start_cf","Farbfluss starten","Effekte", ImplementationStatus.AVAILABLE),
        ProtocolMethod("stop_cf","Farbfluss stoppen","Effekte", ImplementationStatus.AVAILABLE),
        ProtocolMethod("set_scene","Fertige Szene","Effekte", ImplementationStatus.PARTIAL),
        ProtocolMethod("set_default","Standardzustand dauerhaft speichern","Erweitert", ImplementationStatus.NOT_IMPLEMENTED),
        ProtocolMethod("cron_add","Ausschalt-Timer anlegen","Erweitert", ImplementationStatus.NOT_IMPLEMENTED),
        ProtocolMethod("cron_get","Ausschalt-Timer abfragen","Erweitert", ImplementationStatus.NOT_IMPLEMENTED),
        ProtocolMethod("cron_del","Ausschalt-Timer löschen","Erweitert", ImplementationStatus.NOT_IMPLEMENTED),
        ProtocolMethod("set_adjust","Relativwerte anpassen","Erweitert", ImplementationStatus.NOT_IMPLEMENTED),
        ProtocolMethod("set_music","Yeelight Musikmodus über Rückverbindung","Erweitert", ImplementationStatus.NOT_IMPLEMENTED),
        ProtocolMethod("set_name","Name direkt in Lampe schreiben","Erweitert", ImplementationStatus.NOT_IMPLEMENTED),
        ProtocolMethod("adjust_bright","Helligkeit relativ","Relativ", ImplementationStatus.NOT_IMPLEMENTED),
        ProtocolMethod("adjust_ct","Farbtemperatur relativ","Relativ", ImplementationStatus.NOT_IMPLEMENTED),
        ProtocolMethod("adjust_color","Farbe relativ","Relativ", ImplementationStatus.NOT_IMPLEMENTED),
        ProtocolMethod("bg_set_rgb","Hintergrund RGB","Hintergrund", ImplementationStatus.NOT_IMPLEMENTED),
        ProtocolMethod("bg_set_hsv","Hintergrund HSV","Hintergrund", ImplementationStatus.NOT_IMPLEMENTED),
        ProtocolMethod("bg_set_ct_abx","Hintergrund Farbtemperatur","Hintergrund", ImplementationStatus.NOT_IMPLEMENTED),
        ProtocolMethod("bg_start_cf","Hintergrund Farbfluss starten","Hintergrund", ImplementationStatus.NOT_IMPLEMENTED),
        ProtocolMethod("bg_stop_cf","Hintergrund Farbfluss stoppen","Hintergrund", ImplementationStatus.NOT_IMPLEMENTED),
        ProtocolMethod("bg_set_scene","Hintergrund Szene","Hintergrund", ImplementationStatus.NOT_IMPLEMENTED),
        ProtocolMethod("bg_set_default","Hintergrund Standardzustand","Hintergrund", ImplementationStatus.NOT_IMPLEMENTED),
        ProtocolMethod("bg_set_power","Hintergrund Ein/Aus","Hintergrund", ImplementationStatus.NOT_IMPLEMENTED),
        ProtocolMethod("bg_set_bright","Hintergrund Helligkeit","Hintergrund", ImplementationStatus.NOT_IMPLEMENTED),
        ProtocolMethod("bg_set_adjust","Hintergrund Relativwerte","Hintergrund", ImplementationStatus.NOT_IMPLEMENTED),
        ProtocolMethod("bg_toggle","Hintergrund umschalten","Hintergrund", ImplementationStatus.NOT_IMPLEMENTED),
        ProtocolMethod("dev_toggle","Vorder-/Hintergrund gleichzeitig umschalten","Hintergrund", ImplementationStatus.NOT_IMPLEMENTED),
        ProtocolMethod("bg_adjust_bright","Hintergrund Helligkeit relativ","Relativ", ImplementationStatus.NOT_IMPLEMENTED),
        ProtocolMethod("bg_adjust_ct","Hintergrund Farbtemperatur relativ","Relativ", ImplementationStatus.NOT_IMPLEMENTED),
        ProtocolMethod("bg_adjust_color","Hintergrund Farbe relativ","Relativ", ImplementationStatus.NOT_IMPLEMENTED),
    )
    fun reportedButNotImplemented(methods: Set<String>): List<ProtocolMethod> =
        this.methods.filter {
            it.id in methods && it.status == ImplementationStatus.NOT_IMPLEMENTED
        }
    fun unknownReported(methods: Set<String>): Set<String> =
        methods - this.methods.map { it.id }.toSet()
}
