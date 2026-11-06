package io.github.lxsdd.yeelightloftdj

/**
 * Durable per-IP firmware-quota journal with its own private lock.
 *
 * Never synchronize this on YeelightLan: startMusicMode/stopMusicMode hold
 * that object's monitor and may enter DeviceCommandGate. Quota callbacks
 * arrive from inside the gate, so a global LAN monitor here causes ABBA.
 *
 * The read/modify/commit transaction is serialized across different IPs.
 * A failed disk commit throws; DeviceCommandGate must then retain the
 * quarantine in memory and must not unlock an unpersisted recovery.
 */
internal class QuotaPreferenceJournal(
    private val readBlocked: () -> Set<String>,
    private val commitBlocked: (Set<String>) -> Boolean,
) {
    private val monitor = Any()

    fun snapshot(): Set<String> = synchronized(monitor) {
        readBlocked().toSet()
    }

    fun update(ip: String, blocked: Boolean) {
        require(ip.isNotBlank())
        synchronized(monitor) {
            val updated = readBlocked().toMutableSet()
            if (blocked) updated.add(ip) else updated.remove(ip)
            check(commitBlocked(updated.toSet())) {
                "LAN-Quarantäne konnte nicht dauerhaft gespeichert werden"
            }
        }
    }
}
