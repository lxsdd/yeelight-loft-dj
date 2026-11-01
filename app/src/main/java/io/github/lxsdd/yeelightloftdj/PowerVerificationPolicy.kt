package io.github.lxsdd.yeelightloftdj

/**
 * Observed evidence for one explicit set_power request.
 * Durations are cumulative milliseconds from the start of the attempt.
 * No false assumption that an accepted command changed the actual bulb.
 */
enum class PowerOutcome {
    VERIFIED, ACK_ONLY, MISMATCH, REJECTED, NO_ACK, UNREACHABLE, PROTECTED, UNSUPPORTED,
}

data class PowerProbe(
    val outcome: PowerOutcome,
    val connectMs: Long? = null,
    val ackMs: Long? = null,
    val statusMs: Long? = null,
    val totalMs: Long = 0L,
    val message: String = "",
) {
    val commandAccepted: Boolean
        get() = outcome in setOf(PowerOutcome.VERIFIED, PowerOutcome.ACK_ONLY, PowerOutcome.MISMATCH)
}

object PowerVerificationPolicy {
    /** A single explicit request is never repeated after an ambiguous reply. */
    fun outcome(accepted: Boolean, requested: Boolean, observed: Boolean?): PowerOutcome =
        when {
            !accepted -> PowerOutcome.NO_ACK
            observed == null -> PowerOutcome.ACK_ONLY
            observed == requested -> PowerOutcome.VERIFIED
            else -> PowerOutcome.MISMATCH
        }

    fun summarize(results: Collection<PowerProbe>): String {
        val verified = results.count { it.outcome == PowerOutcome.VERIFIED }
        val accepted = results.count { it.outcome == PowerOutcome.ACK_ONLY }
        val mismatch = results.count { it.outcome == PowerOutcome.MISMATCH }
        val failed = results.count { it.outcome in setOf(PowerOutcome.NO_ACK,
            PowerOutcome.UNREACHABLE, PowerOutcome.REJECTED, PowerOutcome.UNSUPPORTED) }
        val protected = results.count { it.outcome == PowerOutcome.PROTECTED }
        return "$verified/${results.size} Zustand bestätigt" +
            (if (accepted > 0) " · $accepted Befehl(e) OK, Status unbekannt" else "") +
            (if (mismatch > 0) " · $mismatch Abweichung(en)" else "") +
            (if (failed > 0) " · $failed Fehler/unbestätigt" else "") +
            (if (protected > 0) " · $protected geschützt" else "")
    }
}
