package io.github.lxsdd.yeelightloftdj

/**
 * Pure selection rules. The runtime keeps per-device failure details and
 * persists stop/recovery markers independently from this selection.
 */
internal object SceneTargetPolicy {
    data class FlowTargets(val supported: List<YeelightDevice>, val unsupported: List<YeelightDevice>)

    fun flowTargets(devices: List<YeelightDevice>): FlowTargets {
        // A manually added bulb can be rediscovered under a different id.
        // Never attempt to control the same physical IP twice in one scene.
        val distinct = devices.distinctBy { it.ip }
        return FlowTargets(
            supported = distinct.filter { it.canFlow },
            unsupported = distinct.filterNot { it.canFlow },
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
