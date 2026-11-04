package io.github.lxsdd.yeelightloftdj

import kotlin.math.roundToInt

/**
 * Convert a freshly read, in-memory Yeelight snapshot to export-only data.
 * ipToGoogleHomeRef is an explicit mapping supplied by the user; IP keys never
 * appear in the resulting HomeLamp objects or generated YAML.
 *
 * The caller must fetch current state through the existing device gate (not
 * cached recovery snapshots), respect quarantine and never auto-probe devices.
 */
object GoogleHomeSnapshotAdapter {
    fun fromFreshSnapshots(
        ipToGoogleHomeRef: Map<String, String>,
        freshByIp: Map<String, LampSnapshot>,
    ): List<HomeLamp> {
        require(ipToGoogleHomeRef.isNotEmpty() && ipToGoogleHomeRef.keys == freshByIp.keys) {
            "Alle gewählten Lampen benötigen einen frischen Status und eine Google-Home-Zuordnung"
        }
        return ipToGoogleHomeRef.map { (localKey, googleRef) ->
            val current = freshByIp.getValue(localKey)
            val color = if (!current.isOn) null else when (current.colorMode) {
                1 -> current.rgb?.let(HomeColor::Rgb)
                2 -> current.temperature?.let(HomeColor::Temperature)
                3 -> if (current.hue != null && current.saturation != null) {
                    HomeColor.Rgb(hsvToRgb(current.hue, current.saturation))
                } else null
                else -> null
            }
            require(!current.isOn || color != null) {
                "Unvollständiger Farbzustand; Export ohne sichere Farbwerte abgebrochen"
            }
            HomeLamp(googleRef, current.isOn, current.brightness, color)
        }
    }

    private fun hsvToRgb(hue: Int, satPercent: Int): Int {
        require(hue in 0..359 && satPercent in 0..100)
        val s = satPercent / 100.0
        val h = hue / 60.0
        val chroma = s
        val x = chroma * (1 - kotlin.math.abs(h % 2 - 1))
        val m = 1 - chroma
        val (r, g, b) = when (h.toInt()) {
            0 -> Triple(chroma, x, 0.0)
            1 -> Triple(x, chroma, 0.0)
            2 -> Triple(0.0, chroma, x)
            3 -> Triple(0.0, x, chroma)
            4 -> Triple(x, 0.0, chroma)
            else -> Triple(chroma, 0.0, x)
        }
        fun byte(c: Double) = ((c + m) * 255).roundToInt().coerceIn(0, 255)
        return (byte(r) shl 16) or (byte(g) shl 8) or byte(b)
    }
}