package io.github.lxsdd.yeelightloftdj

/**
 * Separate one-bulb transport experiment, never an unrestricted DJ show.
 * Status, start and final read use bounded normal control. ALL RGB writes
 * are possible only on an already active set_music reverse connection.
 */
internal object MinimalColor4ProbePolicy {
    const val DURATION_MS = 8_000L
    const val MIN_FRAME_MS = 1_000L
    const val MAX_RGB_FRAMES = 8

    fun refusal(
        selected: List<YeelightDevice>,
        blockedIps: Set<String>,
        recoveryPending: Boolean,
        running: Boolean,
        unconfirmedIps: Set<String>,
    ): String? {
        if (running) return "Es läuft bereits eine Aufnahme oder Lichtaktion."
        if (recoveryPending) return "Ungeklärte Wiederherstellung zuerst abschließen."
        val single = selected.distinctBy { it.ip }.singleOrNull()
            ?: return "Bitte genau eine Color4 auswählen."
        if (single.ip in blockedIps) return "Firmware-Quota: diese Color4 bleibt gesperrt."
        if (single.ip in unconfirmedIps) return "Voriger Music-Probe nicht lesend beendet."
        if (!(single.model.equals("color4", true) ||
            single.model.endsWith(".color4", true))) {
            return "Gerät wurde nicht eindeutig als Color4 erkannt."
        }
        if (!YeelightLan.supportsMusicMode(single)) {
            return "Color4 muss set_music und set_rgb über SUPPORT bestätigen."
        }
        return null
    }

    fun canSend(frame: MusicFrame?, elapsedMs: Long, lastSentMs: Long, attempts: Int): Boolean =
        frame != null && attempts in 0 until MAX_RGB_FRAMES &&
            elapsedMs in 0 until DURATION_MS &&
            elapsedMs >= lastSentMs && elapsedMs - lastSentMs >= MIN_FRAME_MS &&
            Color4MusicFrameDispatch.isAudible(frame)
}
