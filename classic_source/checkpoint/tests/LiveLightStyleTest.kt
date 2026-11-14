package loftdj.classic

/** Synthetic JVM regressions. NEVER send device or LAN commands. */
fun main() {
    val flow = ClassicLiveLightStyle(minFrameIntervalMs = 300L)
    val quietFrames = mutableListOf<ClassicLiveLightStyle.Frame>()
    for (t in 0L..15_000L step 25L) {
        flow.accept(t, false, .01, .01, .01, .01)?.let(quietFrames::add)
    }
    check(quietFrames.size in 49..51) { "quiet flow must be rate capped" }
    check(quietFrames.map { it.rgb }.distinct().size >= 10) { "quiet must have gradual hue progression" }
    check(quietFrames.all { it.brightness in 12..18 }) { "quiet must stay dim" }

    val lively = ClassicLiveLightStyle()
    val frames = mutableListOf<ClassicLiveLightStyle.Frame>()
    for (t in 0L..12_000L step 25L) {
        val beat = t % 450L == 0L
        lively.accept(t, beat, if (beat) .99 else .45, .75, .5, .25)?.let(frames::add)
    }
    check(frames.size in 40..41) { "high-BPM output must stay bounded" }
    check(frames.map { it.rgb }.distinct().size >= 6) { "full HSV diversity" }
    check(frames.maxOf { it.brightness } > quietFrames.maxOf { it.brightness } + 20) {
        "music intensity must influence brightness"
    }
    check(frames.all { it.brightness in 12..100 })

    val replay = ClassicLiveLightStyle()
    check(replay.accept(100L, false, .1, .1, .1, .1) != null)
    check(replay.accept(100L, true, .9, .9, .9, .9) == null)
    check(replay.accept(50L, true, .9, .9, .9, .9) == null)
    check(replay.accept(350L, false, .1, .1, .1, .1) != null)
    replay.reset()
    check(replay.accept(0L, false, .1, .1, .1, .1) != null)
    for (bad in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY, 1.1)) {
        check(runCatching { replay.accept(400L, false, bad, .1, .1, .1) }.isFailure)
    }
    check(runCatching { ClassicLiveLightStyle(minFrameIntervalMs = 50L) }.isFailure)
    check(runCatching { ClassicLiveLightStyle(quietThreshold = Double.NaN) }.isFailure)
    // Live scene switches do not reset timestamps, open network channels or
    // bypass the per-device output pacing. In AMBIENT, keep hue flowing
    // independently of beat; in BEAT, hold hue between audio onsets.
    val dynamic = ClassicLiveLightStyle(minFrameIntervalMs = 300L)
    check(dynamic.accept(0L, false, .25, .25, .25, .25) != null)
    check(dynamic.setScene("AMBIENT"))
    check(dynamic.accept(150L, false, .25, .25, .25, .25) == null)
    val ambientFrame = dynamic.accept(300L, false, .25, .25, .25, .25)
    check(ambientFrame != null && ambientFrame.brightness in 12..75)
    check(dynamic.setScene("BEAT"))
    val stationary = dynamic.accept(600L, false, .25, .25, .25, .25)
    check(stationary != null && stationary.rgb == ambientFrame.rgb)
    val beatFrame = dynamic.accept(900L, true, .9, .9, .9, .9)
    check(beatFrame != null && beatFrame.rgb != stationary.rgb)
    check(dynamic.setScene("SPECTRUM"))
    check(!dynamic.setScene("UNSUPPORTED"))
    check(!dynamic.setScene("BEAT;set_power"))
    check(dynamic.accept(1200L, false, .4, .4, .4, .4) != null)

    println("PASS live light: quiet HSV flow, loudness, bounded 4-Hz frames, rewind, bad input, live scenes")
}
