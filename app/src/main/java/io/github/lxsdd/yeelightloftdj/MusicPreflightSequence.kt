package io.github.lxsdd.yeelightloftdj

/**
 * Sequential read-only LAN admission for explicitly selected bulbs.
 * First failure stops the entire preflight immediately; no retries, no
 * optimistic fallback, and no simultaneous TCP probing of other lamps.
 */
internal object MusicPreflightSequence {
    fun <T> capture(
        selected: List<YeelightDevice>,
        readAndValidate: (YeelightDevice) -> T,
    ): Map<String, T> {
        require(selected.map { it.ip }.distinct().size == selected.size) {
            "Music-Preflight: doppelte Geräte-IP in der Auswahl"
        }
        val snapshots = linkedMapOf<String,T>()
        selected.forEach { device ->
            snapshots[device.ip] = readAndValidate(device)
        }
        return snapshots
    }
}
