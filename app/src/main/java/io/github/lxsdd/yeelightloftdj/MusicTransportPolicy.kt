package io.github.lxsdd.yeelightloftdj

/**
 * A Color4 only participates in continuous live DJ updates when its own
 * discovery SUPPORT list confirms set_music AND set_rgb. Never silently
 * feed real-time RGB commands through the classic quota-limited TCP path.
 *
 * This does not disable single manual LAN changes or firmware-native scenes.
 */
internal object MusicTransportPolicy {
    fun canArmForMusic(device: YeelightDevice): Boolean {
        val model = device.model.trim()
        val isColor4 = model.equals("color4", ignoreCase = true) ||
            model.endsWith(".color4", ignoreCase = true)
        // A TCP-only discovery has no model/SUPPORT evidence. It could still
        // be a Color4; do not enable a silent regular-LAN beat fallback.
        val isUnidentified = model.isEmpty() ||
            model.equals("unknown", ignoreCase = true) ||
            model.equals("Yeelight", ignoreCase = true) ||
            model.equals("Yeelight (LAN)", ignoreCase = true)
        return !(isColor4 || isUnidentified) || YeelightLan.supportsMusicMode(device)
    }

    fun eligible(
        devices: List<YeelightDevice>,
        quarantinedIps: Set<String>,
    ): List<YeelightDevice> = devices.distinctBy { it.ip }.filter {
        it.ip !in quarantinedIps && canArmForMusic(it)
    }
}
