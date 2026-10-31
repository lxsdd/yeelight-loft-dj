package io.github.lxsdd.yeelightloftdj

/** Yeelight start_cf frame: duration in ms, color mode=1 (RGB), packed RGB, brightness. */
data class FlowFrame(val durationMs: Int, val rgb: Int, val brightness: Int) {
    init {
        require(durationMs >= 50)
        require(rgb in 0..0xFFFFFF)
        require(brightness in 1..100)
    }
    fun encode(): String = "$durationMs,1,$rgb,$brightness"
}

data class LoftScene(
    val id: String,
    val name: String,
    val description: String,
    val colors: List<Long>,
    val frames: List<FlowFrame>,
) {
    /** Offset a lamp's starting frame to create a spatially staggered flow. */
    fun expression(phase: Int = 0): String {
        require(frames.size >= 2)
        val shift = Math.floorMod(phase, frames.size)
        return (frames.drop(shift) + frames.take(shift)).joinToString(",") { it.encode() }
    }
}

private fun frame(ms: Int, color: Int, brightness: Int = 65) = FlowFrame(ms, color, brightness)

object SceneLibrary {
    val scenes = listOf(
        LoftScene("aurora", "Loft Aurora", "Sanfte Nordlicht-Wellen", listOf(0xFF13CBB8, 0xFF2672E5, 0xFF9E49EF), listOf(
            frame(7500, 0x13CBB8, 55), frame(8300, 0x2672E5, 48),
            frame(7200, 0x9E49EF, 50), frame(8100, 0x16AF80, 60)
        )),
        LoftScene("sunset", "Loft Sunset", "Warme Farben durch dein Loft", listOf(0xFFFFBD63, 0xFFFA7048, 0xFF8C43CC), listOf(
            frame(8400, 0xFFBD63, 62), frame(9100, 0xFA7048, 57),
            frame(8000, 0xE3428D, 47), frame(9100, 0x8C43CC, 40)
        )),
        LoftScene("neon", "Neon Club", "Leuchtende Farben und Akzente", listOf(0xFFED43CD, 0xFF04C5F7, 0xFF5D40FF), listOf(
            frame(1600, 0xED43CD, 78), frame(1600, 0x04C5F7, 78),
            frame(1600, 0x5D40FF, 76), frame(1600, 0x2DF5A3, 76)
        )),
        LoftScene("fire", "Fireplace", "Warmes, weiches Flackern", listOf(0xFFFFB246, 0xFFEF7329, 0xFFFFD48B), listOf(
            frame(700, 0xFFB246, 58), frame(900, 0xEF7329, 42),
            frame(500, 0xFFD48B, 64), frame(1200, 0xEF872E, 49),
            frame(600, 0xFFA132, 56), frame(1100, 0xF26A24, 46)
        ))
    )
}
