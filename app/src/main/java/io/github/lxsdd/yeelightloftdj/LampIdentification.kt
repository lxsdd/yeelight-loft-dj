package io.github.lxsdd.yeelightloftdj

/**
 * Device capability-aware identify flash. Unknown/white-only lamps are never
 * sent RGB blindly. Snapshot is mandatory for safe static restoration.
 */
data class IdentifyPlan(val pulse: List<LampCommand>, val restore: List<LampCommand>, val kind: String)

object LampIdentification {
    fun prepare(snapshot: LampSnapshot, methods: Set<String>): IdentifyPlan {
        val available: (String) -> Boolean = { methods.isEmpty() || it in methods }
        require(available("set_power") && available("set_bright")) {
            "Lampe kann nicht sicher für die Identifizierung geschaltet oder gedimmt werden"
        }
        // A previously active colour mode may only be changed if its exact
        // previous colour can be reconstructed. Brightness-only never alters hue.
        val safeColorRestore = SceneRecovery.supported(snapshot)
        val mayRgb = available("set_rgb") && safeColorRestore &&
            (snapshot.colorMode == 1 || !snapshot.isOn)
        val mayWhite = available("set_ct_abx") && safeColorRestore &&
            (snapshot.colorMode == 2 || !snapshot.isOn)
        val pulse = mutableListOf<LampCommand>()
        if (!snapshot.isOn) pulse += LampCommands.power(true)
        val kind = when {
            mayRgb -> {
                pulse += LampCommands.rgb(0xA877FF)
                pulse += LampCommands.brightness(70)
                "RGB"
            }
            mayWhite -> {
                pulse += LampCommands.temperature(5000)
                pulse += LampCommands.brightness(90)
                "Weißlicht"
            }
            else -> {
                pulse += LampCommands.brightness(if (snapshot.brightness >= 55) 18 else 95)
                "Helligkeit"
            }
        }
        val restore = if (!snapshot.isOn) listOf(LampCommands.power(false))
            else if (safeColorRestore) SceneRecovery.commands(snapshot).drop(1)
            else listOf(LampCommands.brightness(snapshot.brightness))
        require(restore.all { available(it.method) }) {
            "Ursprüngliche Helligkeit/Farbe lässt sich nicht sicher wiederherstellen"
        }
        return IdentifyPlan(pulse, restore, kind)
    }
}
