package io.github.lxsdd.yeelightloftdj

/**
 * Optional manual RGB override for ONE exported scene.
 *
 * A Google Home color-wheel request is not a measurement of the LED output.
 * No hardware color space or LUT can be inferred from a single observed mismatch.
 * No network, device state, global device profile or preferences are changed.
 */
object GoogleHomeColorTuning {
    private val validHex = Regex("#?[0-9A-Fa-f]{6}")

    fun applyOverrides(lamps: List<HomeLamp>, overrideByRef: Map<String, String>): List<HomeLamp> {
        if (overrideByRef.isEmpty()) return lamps
        require(overrideByRef.keys.all { key -> lamps.any { it.deviceAndRoom == key } }) {
            "RGB-Korrektur hat kein eindeutig zugeordnetes Google-Home-Gerät"
        }
        return lamps.map { lamp ->
            val raw = overrideByRef[lamp.deviceAndRoom] ?: return@map lamp
            require(lamp.on && lamp.color is HomeColor.Rgb) {
                "RGB-Korrektur nur für eingeschaltete Lampen im RGB-Modus"
            }
            val hex = raw.trim()
            require(validHex.matches(hex)) {
                "RGB-Farbwert: sechs Hex-Zeichen (z. B. #FF7700) erforderlich"
            }
            lamp.copy(color = HomeColor.Rgb(hex.removePrefix("#").toInt(16)))
        }
    }
}
