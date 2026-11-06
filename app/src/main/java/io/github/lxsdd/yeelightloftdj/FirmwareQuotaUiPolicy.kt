package io.github.lxsdd.yeelightloftdj

/**
 * Pure user-facing reporting for a PER-DEVICE firmware quota refusal.
 * Never bypass DeviceCommandGate, discard recovery or retry on selection.
 */
internal object FirmwareQuotaUiPolicy {
    fun failedPreflight(name: String, ip: String): String =
        "Music-Preflight: " + name.trim() + " (IP-Endung ..." +
            ip.substringAfterLast('.') +
            ") meldet client quota exceeded. Nur diese Lampe wird " +
            "LAN-quarantänisiert; andere Lampen bleiben separat auswählbar."

    fun changedSelection(label: String, count: Int, quarantined: List<String>): String =
        "Ziel aktualisiert: " + label + " · " + count + " Lampen" +
            if (quarantined.isEmpty()) ""
            else " · " + quarantined.size + " Lampe(n) separat LAN-geschützt"

    fun quarantinedSummary(quarantined: List<String>): String =
        if (quarantined.isEmpty()) ""
        else "LAN-Quarantäne je Gerät: " +
            quarantined.take(4).joinToString(" · ") +
            (if (quarantined.size > 4) " · +" + (quarantined.size - 4) else "") +
            ". Andere Geräte bleiben bedienbar; Recovery nur in Gerätedetails."
}
