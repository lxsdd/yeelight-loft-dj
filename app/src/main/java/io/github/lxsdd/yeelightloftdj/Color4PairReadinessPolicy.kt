package io.github.lxsdd.yeelightloftdj

/**
 * Zero-network eligibility check for a future two-lamp acceptance stage.
 * Passing this check is NOT an authorization to run a multi-device show:
 * individual optical, restore, and official-app validations are still needed.
 */
internal object Color4PairReadinessPolicy {
    fun refusal(
        selected: List<YeelightDevice>,
        blockedIps: Set<String>,
        recoveryPending: Boolean,
    ): String? {
        val distinct = selected.distinctBy { it.ip }
        if (distinct.size != 2) return "Für die Zwei-Lampen-Eignung genau zwei Color4 wählen."
        if (recoveryPending) return "Offene Wiederherstellung: Mehrlampenbetrieb gesperrt."
        for (device in distinct) {
            if (device.ip in blockedIps) return "Quarantäne einer Lampe: Mehrlampenbetrieb gesperrt."
            if (!device.model.equals("color4", ignoreCase = true) &&
                !device.model.endsWith(".color4", ignoreCase = true))
                return "Beide Geräte müssen eindeutig als Color4 identifiziert sein."
            if (!YeelightLan.supportsMusicMode(device))
                return "Beide Color4 müssen set_music und set_rgb melden."
        }
        return null
    }
}
