package loftdj.classic
import kotlin.math.PI
import kotlin.math.sin

fun main() {
    val fft = ClassicPcmBands(16000, 1024)
    check(fft.bands(ShortArray(1024)).all { it == 0.0 })
    val frequencies = listOf(90.0, 400.0, 1700.0, 5200.0)
    frequencies.forEachIndexed { expected, hz ->
        val samples = ShortArray(1024) { i -> (sin(2 * PI * hz * i / 16000) * 30000).toInt().toShort() }
        val result = fft.bands(samples)
        check(result.all { it.isFinite() && it in 0.0..1.0 })
        check(result.indices.maxByOrNull { result[it] } == expected) { "frequency $hz mapped to ${result.toList()}" }
    }
    try { fft.bands(ShortArray(512)); error("accepted wrong window") } catch (_: IllegalArgumentException) {}
    for (size in listOf(0, 300, 8192)) {
        try { ClassicPcmBands(16000, size); error("accepted $size") } catch (_: IllegalArgumentException) {}
    }
    println("PASS PCM FFT: silence, four frequency bands, invalid window rejection")
}
