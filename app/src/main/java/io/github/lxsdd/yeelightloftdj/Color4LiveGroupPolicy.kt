package io.github.lxsdd.yeelightloftdj

/** Safety admission based on verified per-lamp Music capabilities, not model-name
 * guesses or an arbitrary four-bulb ceiling. No network traffic or LAN fallback.
 */
internal data class Color4LiveCandidate(
    val ip: String, val model: String, val music: Boolean, val brightness: Boolean = true
)
internal object Color4LiveGroupPolicy {

    fun refusal(
        candidates: List<Color4LiveCandidate>,
        quotaBlocked: Set<String>,
        unconfirmedMusic: Set<String>,
        otherAction: Boolean,
        requireBrightness: Boolean = false,
    ): String? {
        if (otherAction) return "Es läuft eine andere Lichtaktion oder Wiederherstellung."
        if (candidates.isEmpty()) return "Zuerst mindestens eine Color4 auswählen."
        if (candidates.map { it.ip }.distinct().size != candidates.size)
            return "Eine Lampen-IP wurde mehrfach ausgewählt."
        candidates.forEach { d ->
            if (d.ip in quotaBlocked) return "Eine ausgewählte Color4 hat eine Quota-Sperre."
            if (d.ip in unconfirmedMusic) return "Music-Ende einer Color4 ist ungeklärt."
            if (!d.music)
                return "Nicht jede ausgewählte Lampe meldet set_music und set_rgb. " +
                    "Nur bestätigte RGB-Musiklampen auswählen."
            // set_bright is optional: dynamic color and RGB dimming still work.
            // Never issue an unadvertised brightness command.
        }
        return null
    }
}
