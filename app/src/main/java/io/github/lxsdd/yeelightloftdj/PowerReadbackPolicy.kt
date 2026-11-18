package io.github.lxsdd.yeelightloftdj

/**
 * Color4 firmware revisions are reported to have broken get_prop and TCP
 * lifetime behavior. A manual Power button must not silently double the
 * number of LAN commands merely to provide visual state confirmation.
 *
 * Return ACK_ONLY for a successful manual write in the conservative case;
 * the user can explicitly request a status read if desired.
 */
internal object PowerReadbackPolicy {
    fun shouldAutomaticallyRead(device: YeelightDevice): Boolean {
        val model = device.model.trim().lowercase()
        if (model == "color4" || model.endsWith(".color4") ||
            model.isEmpty() || model == "unknown" || model == "yeelight" ||
            model == "yeelight (lan)") return false
        return "get_prop" in device.methods
    }
}
