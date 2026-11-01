package io.github.lxsdd.yeelightloftdj

/** Protect a firmware-rejected music stop from being replayed by UI retries. */
internal object MusicStopSafety {
    fun unconfirmed(message: String?): Boolean =
        message?.contains("set_music", ignoreCase = true) == true

    fun permitsStop(ip: String, unconfirmedIps: Set<String>): Boolean =
        ip !in unconfirmedIps
}
