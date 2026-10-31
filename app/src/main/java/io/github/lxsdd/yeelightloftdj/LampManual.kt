package io.github.lxsdd.yeelightloftdj

/** Protocol-safe commands. A slider only sends once the user releases it. */
data class LampCommand(val method: String, val params: List<Any>)

object LampCommands {
    fun power(on: Boolean): LampCommand =
        LampCommand("set_power", listOf(if (on) "on" else "off", "smooth", 300))

    fun brightness(value: Int): LampCommand {
        require(value in 1..100) { "Helligkeit muss zwischen 1 und 100 liegen" }
        return LampCommand("set_bright", listOf(value, "smooth", 300))
    }

    fun rgb(value: Int): LampCommand {
        require(value in 0..0xFFFFFF) { "Ungültige RGB-Farbe" }
        return LampCommand("set_rgb", listOf(value, "smooth", 300))
    }

    fun temperature(value: Int): LampCommand {
        require(value in 1700..6500) { "Farbtemperatur muss zwischen 1700 und 6500 K liegen" }
        return LampCommand("set_ct_abx", listOf(value, "smooth", 300))
    }

    fun hsv(hue: Int, saturation: Int): LampCommand {
        require(hue in 0..359 && saturation in 0..100) { "Ungültiger HSV-Wert" }
        return LampCommand("set_hsv", listOf(hue, saturation, "smooth", 300))
    }
}

/**
 * Device-reported snapshot; never infer live state from a previous button tap.
 * Identifying a bulb uses this snapshot for a best-effort restoration.
 */
data class LampSnapshot(
    val isOn: Boolean,
    val brightness: Int,
    val colorMode: Int,
    val rgb: Int?,
    val temperature: Int?,
    val hue: Int?,
    val saturation: Int?,
) {
    fun restoreCommands(): List<LampCommand> {
        if (!isOn) return listOf(LampCommands.power(false))
        val color = when (colorMode) {
            1 -> rgb?.let { LampCommands.rgb(it) }
            2 -> temperature?.let { LampCommands.temperature(it) }
            3 -> if (hue != null && saturation != null) LampCommands.hsv(hue, saturation) else null
            else -> null
        }
        return listOfNotNull(color) + LampCommands.brightness(brightness)
    }

    companion object {
        val properties = listOf("power", "bright", "color_mode", "rgb", "ct", "hue", "sat")

        fun fromValues(values: List<String>): LampSnapshot? {
            if (values.size < 2 || values[0] !in listOf("on", "off")) return null
            val brightness = values[1].toIntOrNull()?.takeIf { it in 1..100 } ?: return null
            fun read(index: Int, range: IntRange): Int? =
                values.getOrNull(index)?.toIntOrNull()?.takeIf { it in range }
            return LampSnapshot(
                isOn = values[0] == "on",
                brightness = brightness,
                colorMode = read(2, 1..3) ?: 0,
                rgb = read(3, 0..0xFFFFFF),
                temperature = read(4, 1700..6500),
                hue = read(5, 0..359),
                saturation = read(6, 0..100),
            )
        }
    }
}
