package io.github.lxsdd.yeelightloftdj

/**
 * Pure, deterministic ordering: both the compact wizard and the device arrows
 * use this same operation. A missing ID or invalid position leaves the list
 * untouched; no lamp commands are issued while sorting.
 */
object LampOrder {
    fun relocate(current: List<String>, deviceId: String, position: Int): List<String> {
        require(current.size == current.distinct().size) { "Doppelte Geräte-IDs" }
        if (deviceId !in current || position !in current.indices) return current
        val old = current.indexOf(deviceId)
        if (old == position) return current
        return current.toMutableList().apply {
            removeAt(old)
            add(position, deviceId)
        }
    }
}
