package io.github.lxsdd.yeelightloftdj

/** Input-only safety admission; no network traffic and no LAN RGB fallback. */
internal data class Color4LiveCandidate(val ip: String,val model: String,val music: Boolean)
internal object Color4LiveGroupPolicy {
    const val MAX_TARGETS = 4

    fun refusal(
        candidates: List<Color4LiveCandidate>,
        quotaBlocked: Set<String>,
        unconfirmedMusic: Set<String>,
        otherAction: Boolean,
    ): String? {
        if (otherAction) return "Es läuft eine andere Lichtaktion oder Wiederherstellung."
        if (candidates.isEmpty()) return "Zuerst mindestens eine Color4 auswählen."
        if (candidates.size > MAX_TARGETS) return "RC13 erlaubt zunächst höchstens vier Color4."
        if (candidates.map { it.ip }.distinct().size != candidates.size)
            return "Eine Lampen-IP wurde mehrfach ausgewählt."
        candidates.forEach { d ->
            if (d.ip in quotaBlocked) return "Eine ausgewählte Color4 hat eine Quota-Sperre."
            if (d.ip in unconfirmedMusic) return "Music-Ende einer Color4 ist ungeklärt."
            if (!(d.model.equals("color4", true) || d.model.endsWith(".color4", true)))
                return "Alle ausgewählten Geräte müssen eindeutig Color4 sein."
            if (!d.music) return "Eine ausgewählte Color4 bestätigt set_music/set_rgb nicht."
        }
        return null
    }
}
