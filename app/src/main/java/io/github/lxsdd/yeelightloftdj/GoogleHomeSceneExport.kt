package io.github.lxsdd.yeelightloftdj

/** Explicit Google Home device name and room mapping. No IP addresses or Yeelight IDs. */
data class HomeLamp(
    val deviceAndRoom: String,
    val on: Boolean,
    val brightness: Int = 100,
    val color: HomeColor? = null,
)

/** RGB is an unsigned packed 24-bit value. Temperature is in kelvin. */
sealed interface HomeColor {
    data class Rgb(val value: Int) : HomeColor
    data class Temperature(val kelvin: Int) : HomeColor
}

/** A finite, non-looping, explicit preview sequence; duration until the next step. */
data class HomeSceneStep(val durationMs: Long, val lamps: List<HomeLamp>)

data class HomeExport(val yaml: String, val warnings: List<String>, val actionCount: Int)

/**
 * Generates a Google Home household-automation script only. It never talks to the
 * Google/Yeelight APIs, never sends LAN traffic, and never writes user settings.
 *
 * Delays are precautionary, NOT a guarantee of actual cloud execution pacing or
 * protection against the physical Color4 firmware's still-unknown quota limits.
 */
object GoogleHomeSceneExport {
    const val MAX_LAMPS = 16
    const val MAX_STEPS = 8
    const val MIN_PREVIEW_STEP_MS = 10_000L
    const val MAX_ACTIONS = 128

    /** Capture one fixed arrangement of individually mapped Google Home lamps. */
    fun staticScene(name: String, voicePhrase: String, lamps: List<HomeLamp>): HomeExport {
        val normalized = checkedLamps(lamps)
        val actions = mutableListOf<String>()
        appendState(actions, normalized)
        return build(name, voicePhrase, actions, listOf(
            "Google Home verwendet die hinterlegten Cloud-/Geräteintegrationen; keine lokale LAN-Steuerung.",
            "Befehlsabstände und Farbunterstützung der Yeelight-Geräte sind in Google Home zu validieren."
        ))
    }

    /**
     * Export a few slow steps as a ONE-SHOT storyboard, never as an infinite
     * loop or a high-frequency music/flow/chase simulation.
     */
    fun slowStoryboard(name: String, voicePhrase: String, steps: List<HomeSceneStep>): HomeExport {
        require(steps.size in 2..MAX_STEPS) { "Nur 2 bis $MAX_STEPS einmalige, langsame Szenenabschnitte" }
        val frames = steps.map { step ->
            require(step.durationMs in MIN_PREVIEW_STEP_MS..600_000L) {
                "Schnelle Animation nicht sicher nach Google Home exportierbar (mindestens 10 s je Schritt)"
            }
            checkedLamps(step.lamps)
        }
        val keys = frames.first().map { it.deviceAndRoom }.toSet()
        require(frames.all { it.map(HomeLamp::deviceAndRoom).toSet() == keys }) {
            "Jeder Schritt muss dieselben explizit zugeordneten Lampen enthalten"
        }
        // No hidden per-frame brightness or color-mode side effects. Only a new
        // RGB/Kelvin command per changed lamp is allowed after the first step.
        val firstByKey = frames.first().associateBy(HomeLamp::deviceAndRoom)
        frames.drop(1).forEach { frame ->
            frame.forEach { lamp ->
                val initial = firstByKey.getValue(lamp.deviceAndRoom)
                require(initial.on && lamp.on && lamp.brightness == initial.brightness &&
                    initial.color != null && lamp.color != null) {
                    "Animierte Vorschau unterstützt nur eingeschaltete Lampen mit konstanter Helligkeit"
                }
            }
        }
        val actions = mutableListOf<String>()
        appendState(actions, frames.first())
        var previous = frames.first().associateBy(HomeLamp::deviceAndRoom)
        for (index in 1 until frames.size) {
            actions += delay(steps[index - 1].durationMs)
            val current = frames[index]
            current.forEach { lamp ->
                val prior = previous.getValue(lamp.deviceAndRoom)
                if (lamp.color != prior.color) colorAction(lamp)?.let(actions::add)
            }
            previous = current.associateBy(HomeLamp::deviceAndRoom)
        }
        return build(name, voicePhrase, actions, listOf(
            "Einmalige, langsame Vorschau – keine Endlosschleife, kein Lauflicht und keine Musiksynchronisation.",
            "Google-Home-Verzögerungen und Cloud-Latenzen sind nicht echtzeitfähig.",
            "Echte Color4-LAN-Quoten sind nicht verifiziert; keine Hardware-Freigabe durch diesen Export."
        ))
    }

    private fun checkedLamps(lamps: List<HomeLamp>): List<HomeLamp> {
        require(lamps.size in 1..MAX_LAMPS) { "1 bis $MAX_LAMPS Lampen pro Export" }
        lamps.forEach { lamp ->
            require(lamp.deviceAndRoom.length in 3..120 &&
                lamp.deviceAndRoom == lamp.deviceAndRoom.trim() &&
                lamp.deviceAndRoom.contains(" - ") &&
                lamp.deviceAndRoom.substringBefore(" - ").isNotBlank() &&
                lamp.deviceAndRoom.substringAfterLast(" - ").isNotBlank() &&
                safeText(lamp.deviceAndRoom) &&
                !containsIpv4(lamp.deviceAndRoom)) {
                "Google Home erwartet 'Gerätename - Raumname' gemäß dessen Autovervollständigung"
            }
            require(lamp.brightness in 1..100) { "Helligkeit muss zwischen 1 und 100 liegen" }
            when (val c = lamp.color) {
                is HomeColor.Rgb -> require(c.value in 0..0xFFFFFF)
                is HomeColor.Temperature -> require(c.kelvin in 1700..6500)
                null -> Unit
            }
            if (lamp.on) require(lamp.color != null) { "Eingeschaltete Lampen benötigen eine explizite Farbe/Temperatur" }
        }
        require(lamps.map { it.deviceAndRoom.lowercase(java.util.Locale.ROOT) }.distinct().size == lamps.size) {
            "Eine Google-Home-Lampe darf nur einmal pro Szene vorkommen"
        }
        return lamps.sortedBy { it.deviceAndRoom }
    }

    private fun appendState(actions: MutableList<String>, lamps: List<HomeLamp>) {
        // Off commands are separate from color/brightness updates; no on-then-off race.
        lamps.filterNot(HomeLamp::on).forEach { lamp ->
            actions += "    - type: device.command.OnOff\n      devices: ${quoted(lamp.deviceAndRoom)}\n      on: false"
        }
        val lit = lamps.filter(HomeLamp::on)
        lit.forEach { lamp -> colorAction(lamp)?.let(actions::add) }
        if (lit.isNotEmpty()) {
            // Stage delay prevents consecutive color/brightness commands against
            // the same bulb without deliberately imposing 2 s between all bulbs.
            actions += delay(2_000L)
            lit.groupBy(HomeLamp::brightness).forEach { (brightness, members) ->
                actions += buildString {
                    append("    - type: device.command.BrightnessAbsolute\n")
                    append("      devices:\n")
                    members.forEach { append("      - ${quoted(it.deviceAndRoom)}\n") }
                    append("      brightness: $brightness")
                }
            }
        }
    }

    private fun colorAction(lamp: HomeLamp): String? = when (val c = lamp.color) {
        is HomeColor.Rgb -> "    - type: device.command.ColorAbsolute\n" +
            "      devices: ${quoted(lamp.deviceAndRoom)}\n" +
            "      color:\n        spectrumRGB: ${quoted(c.value.toString(16).uppercase().padStart(6, '0'))}"
        is HomeColor.Temperature -> "    - type: device.command.ColorAbsolute\n" +
            "      devices: ${quoted(lamp.deviceAndRoom)}\n" +
            "      color:\n        temperature: ${c.kelvin}K"
        null -> null
    }

    private fun delay(ms: Long): String {
        require(ms in 1_000L..600_000L && ms % 1_000L == 0L)
        return "    - type: time.delay\n      for: ${ms / 1_000L}sec"
    }

    private fun build(name: String, phrase: String, actions: List<String>, warnings: List<String>): HomeExport {
        require(name.length in 1..64 && safeText(name) && !containsIpv4(name)) { "Ungültiger Szenenname" }
        require(phrase.length in 3..80 && safeText(phrase) && !containsIpv4(phrase)) { "Ungültiger Sprachbefehl" }
        require(actions.size in 1..MAX_ACTIONS) { "Zu viele Google-Home-Aktionen" }
        val yaml = buildString {
            append("metadata:\n")
            append("  name: ${quoted(name)}\n")
            append("  description: \"Aus Yeelight Loft DJ exportierte Lichteinstellung\"\n")
            append("automations:\n")
            append("  - starters:\n")
            append("    - type: assistant.event.OkGoogle\n")
            append("      eventData: query\n")
            append("      is: ${quoted(phrase)}\n")
            append("    actions:\n")
            actions.forEach { append(it); append('\n') }
        }
        require(yaml.toByteArray(Charsets.UTF_8).size <= 48 * 1024) { "Export ist zu groß" }
        return HomeExport(yaml, warnings, actions.size)
    }

    private fun containsIpv4(text: String): Boolean = Regex("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}").containsMatchIn(text)

    private fun safeText(text: String): Boolean = text.none { it.isISOControl() || it == '\u2028' || it == '\u2029' }

    /** Proper YAML double-quoted scalar escaping; text cannot inject actions. */
    private fun quoted(text: String): String = buildString {
        append('"')
        text.forEach { c ->
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                else -> append(c)
            }
        }
        append('"')
    }
}