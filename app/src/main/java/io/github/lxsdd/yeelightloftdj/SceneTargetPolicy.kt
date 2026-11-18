package io.github.lxsdd.yeelightloftdj

/**
 * Pure selection rules. A firmware-quarantined bulb cannot enter a scene
 * before network I/O, state snapshotting or a durable recovery marker.
 */
internal object SceneTargetPolicy {
    data class FlowTargets(
        val supported: List<YeelightDevice>,
        val unsupported: List<YeelightDevice>,
        val blocked: List<YeelightDevice> = emptyList(),
    )

    fun flowTargets(
        devices: List<YeelightDevice>,
        quarantinedIps: Set<String> = emptySet(),
    ): FlowTargets {
        // Never attempt the same physical IP twice (manual + SSDP aliases).
        val distinct = devices.distinctBy { it.ip }
        val allowed = distinct.filterNot { it.ip in quarantinedIps }
        return FlowTargets(
            supported = allowed.filter { it.canFlow },
            unsupported = allowed.filterNot { it.canFlow },
            blocked = distinct.filter { it.ip in quarantinedIps },
        )
    }

    fun loftIds(
        devices: List<YeelightDevice>,
        assignments: Map<String, String>,
        explicitMembers: Set<String>?,
    ): Set<String> = explicitMembers ?: devices.filter {
        assignments[it.id] in setOf("Wohnzimmer", "Esszimmer", "Hobbyraum")
    }.map { it.id }.toSet()
}
