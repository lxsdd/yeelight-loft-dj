package loftdj.classic

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Offline PCM-to-four-band adapter. No recording permissions, network or device writes. */
class ClassicPcmBands(private val sampleRate: Int, private val windowSize: Int = 1024) {
    init {
        require(sampleRate in 8000..192000)
        require(windowSize in 256..4096 && windowSize and (windowSize - 1) == 0)
    }
    private val ranges = doubleArrayOf(20.0, 160.0, 800.0, 4000.0, sampleRate / 2.0)

    /** Input is exactly one signed 16-bit mono frame; values are normalized RMS spectra. */
    fun bands(pcm: ShortArray): DoubleArray {
        require(pcm.size == windowSize)
        val real = DoubleArray(windowSize) { i ->
            (pcm[i].toDouble() / 32768.0) * (0.5 - 0.5 * cos(2.0 * PI * i / (windowSize - 1)))
        }
        val imag = DoubleArray(windowSize)
        var j = 0
        for (i in 1 until windowSize) {
            var bit = windowSize shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val temp = real[i]; real[i] = real[j]; real[j] = temp
            }
        }
        var len = 2
        while (len <= windowSize) {
            val angle = -2.0 * PI / len
            val wr = cos(angle); val wi = sin(angle)
            for (start in 0 until windowSize step len) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val a = start + k; val b = a + len / 2
                    val tr = cr * real[b] - ci * imag[b]
                    val ti = cr * imag[b] + ci * real[b]
                    real[b] = real[a] - tr; imag[b] = imag[a] - ti
                    real[a] += tr; imag[a] += ti
                    val nr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr; cr = nr
                }
            }
            len *= 2
        }
        val power = DoubleArray(4)
        val count = IntArray(4)
        for (k in 1 until windowSize / 2) {
            val hz = k.toDouble() * sampleRate / windowSize
            val band = (0..3).firstOrNull { hz >= ranges[it] && hz < ranges[it + 1] } ?: continue
            power[band] += real[k] * real[k] + imag[k] * imag[k]
            count[band]++
        }
        return DoubleArray(4) { i ->
            if (count[i] == 0) 0.0 else (sqrt(power[i] / count[i]) * 2.0 / windowSize).coerceIn(0.0, 1.0)
        }
    }
}
