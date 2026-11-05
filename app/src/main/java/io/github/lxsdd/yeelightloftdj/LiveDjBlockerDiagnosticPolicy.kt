package io.github.lxsdd.yeelightloftdj

/** Pure, read-only Music admission diagnostics. The global stale-session marker
 * and each lamp's separate safety state are presented independently.
 */
internal data class LiveDjLampBlocker(
    val label: String,
    val quotaBlocked: Boolean,
    val musicUnconfirmed: Boolean,
    val restorePending: Boolean,
    val hasMusicCapability: Boolean,
)
internal object LiveDjBlockerDiagnosticPolicy {
    fun explain(
        previousMusicSession: Boolean,
        activeScene: Boolean,
        stopPending: Boolean,
        lamps: List<LiveDjLampBlocker>,
    ): String? {
        val notices = mutableListOf<String>()
        if (previousMusicSession)
            notices += "Eine frühere Musiklicht-Sitzung ist noch gespeichert. " +
                "Wiederherstellung zuerst pro betroffener Lampe prüfen; " +
                "nicht alle Schutzmarkierungen pauschal löschen."
        if (activeScene)
            notices += "Es ist noch eine Lichtshow/Wiederherstellung als aktiv erfasst."
        if (stopPending) notices += "Ein Music-Stopp ist noch nicht abgeschlossen."
        lamps.forEach { lamp ->
            val reason = when {
                lamp.quotaBlocked -> "Firmware-LAN-Quota; nur ausdrückliche LAN-Leseprüfung"
                lamp.musicUnconfirmed -> "music_on=0 nicht bestätigt; nur lesend nachprüfen"
                lamp.restorePending -> "gespeicherte Wiederherstellung noch offen"
                !lamp.hasMusicCapability -> "set_music + set_rgb nicht bestätigt"
                else -> null
            }
            if (reason != null) notices += lamp.label + ": " + reason
        }
        if (notices.isEmpty()) return null
        return notices.take(6).joinToString("\n") +
            if (notices.size > 6) "\n… und ${notices.size-6} weitere Gründe" else ""
    }
}
