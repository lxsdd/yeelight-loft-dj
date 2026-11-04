package io.github.lxsdd.yeelightloftdj

/**
 * Optional, user-approved downgrade of a Loft DJ flow to a short, finite
 * Google-Home-compatible storyboard. NOT an import of Yeelight Classic scenes.
 * Restoring the original frame timing, phases, brightness changes or looping
 * would require a separate validated local engine, not Google Home YAML.
 */
object GoogleHomeFlowPreview {
    fun approximate(
        scene: LoftScene,
        voicePhrase: String,
        googleDeviceRefs: List<String>,
        userAcceptsApproximation: Boolean,
        sceneName: String? = null,
    ): HomeExport {
        require(userAcceptsApproximation) {
            "Szenenvereinfachung muss ausdrücklich bestätigt werden"
        }
        require(scene.frames.size in 2..GoogleHomeSceneExport.MAX_STEPS) {
            "Nur kurze Verläufe mit 2 bis ${GoogleHomeSceneExport.MAX_STEPS} Frames"
        }
        require(googleDeviceRefs.size in 1..GoogleHomeSceneExport.MAX_LAMPS &&
            googleDeviceRefs.distinct().size == googleDeviceRefs.size) {
            "1 bis ${GoogleHomeSceneExport.MAX_LAMPS} unterschiedliche Zielgeräte erforderlich"
        }
        val firstBrightness = scene.frames.first().brightness
        val steps = scene.frames.map { frame ->
            HomeSceneStep(
                frame.durationMs.toLong().coerceAtLeast(GoogleHomeSceneExport.MIN_PREVIEW_STEP_MS),
                googleDeviceRefs.map { ref ->
                    HomeLamp(ref, true, firstBrightness, HomeColor.Rgb(frame.rgb))
                }
            )
        }
        val result = GoogleHomeSceneExport.slowStoryboard(
            sceneName?.takeIf { it.isNotBlank() } ?: (scene.name + " (Vorschau)"),
            voicePhrase, steps
        )
        val changes = scene.frames.map(FlowFrame::brightness).distinct().size > 1
        return result.copy(warnings = result.warnings + listOfNotNull(
            "Explizite Annäherung: Farbwechsel auf mindestens 10 Sekunden gedehnt; " +
                "alle ausgewählten Lampen wechseln gleichzeitig, keine räumliche Phasenverschiebung.",
            if (changes) "Variable Originalhelligkeit wird nicht animiert; Starthelligkeit bleibt konstant." else null,
            "Die Vorschau endet nach ${scene.frames.size} Schritten; keine automatische Wiederholung."
        ))
    }
}