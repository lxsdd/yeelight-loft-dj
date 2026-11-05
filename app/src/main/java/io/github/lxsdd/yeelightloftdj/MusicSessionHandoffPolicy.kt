package io.github.lxsdd.yeelightloftdj

/** Explicit owner action only. No automatic takeover or unconfirmed release. */
internal enum class MusicHandoffReadDecision { ALREADY_STOPPED, END_ONCE, UNKNOWN }
internal object MusicSessionHandoffPolicy {
    fun canOffer(
        supportsMusic: Boolean,
        isQuarantined: Boolean,
        hasOwnChannel: Boolean,
        otherAction: Boolean,
    ): Boolean = supportsMusic && !isQuarantined && !hasOwnChannel && !otherAction

    fun decidePreflight(musicOn: Boolean?): MusicHandoffReadDecision = when(musicOn) {
        false -> MusicHandoffReadDecision.ALREADY_STOPPED
        true -> MusicHandoffReadDecision.END_ONCE
        null -> MusicHandoffReadDecision.UNKNOWN
    }

    /** ACK alone is not proof that the firmware ended the session. */
    fun verifiedStopped(stopAcked: Boolean, musicOnAfter: Boolean?): Boolean =
        stopAcked && musicOnAfter == false
}
