package loftdj.classic

import kotlin.math.abs
fun main() {
    val a = ClassicBeatAnalyzer()
    var detected = 0
    var final: ClassicBeatAnalyzer.Result? = null
    // 130 BPM; 10ms frames, short synthetic onsets, no prerecorded audio.
    for (t in 0L..6000L step 10L) {
        val onset = (t % 460L) < 30L
        val bands = if (onset) doubleArrayOf(0.95, 0.65, 0.25, 0.10)
                    else doubleArrayOf(0.015, 0.015, 0.015, 0.015)
        val r = a.accept(t, bands)
        if (r.beat) detected++
        final = r
    }
    check(detected >= 10) { "Missed synthetic 130 BPM beats: $detected" }
    check(final!!.bpm != null && abs(final!!.bpm!! - 130.43) < 2.0) { "BPM ${final!!.bpm}" }
    check(final!!.confidence >= 0.6)
    // Continuous high energy must not retrigger every frame.
    val constant = ClassicBeatAnalyzer()
    var count = 0
    for (t in 0L..2000L step 10L) if (constant.accept(t, doubleArrayOf(0.9, 0.9, 0.9, 0.9)).beat) count++
    check(count <= 1) { "Constant energy retriggered $count times" }
    val stale = a.accept(100, doubleArrayOf(0.0, 0.0, 0.0, 0.0))
    check(stale.bpm == null) { "Stale BPM survived clock reset" }
    for (bad in listOf(doubleArrayOf(Double.NaN, 0.0, 0.0, 0.0), doubleArrayOf(-1.0, 0.0, 0.0, 0.0))) {
        try { a.accept(200, bad); error("Bad band accepted") } catch (_: IllegalArgumentException) {}
    }
    try { a.accept(200, doubleArrayOf(1.0, 0.0, 0.0, 0.0), Double.POSITIVE_INFINITY); error("Bad gain accepted") }
    catch (_: IllegalArgumentException) {}
    val silent = ClassicBeatAnalyzer()
    check((0L..5000L step 10L).all { !silent.accept(it, doubleArrayOf(0.0, 0.0, 0.0, 0.0)).beat })
    println("PASS beat analyzer synthetic 130 BPM, constant, stale, invalid, silence")
}
