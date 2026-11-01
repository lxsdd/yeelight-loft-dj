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
        val isColor4 = device.model.equals("color4", ignoreCase = true) ||
            device.model.endsWith(".color4", ignoreCase = true)
        return !isColor4 || YeelightLan.supportsMusicMode(device)
    }

    fun eligible(
        devices: List<YeelightDevice>,
        quarantinedIps: Set<String>,
    ): List<YeelightDevice> = devices.distinctBy { it.ip }.filter {
        it.ip !in quarantinedIps && canArmForMusic(it)
    }
}
