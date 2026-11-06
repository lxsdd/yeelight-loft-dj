package io.github.lxsdd.yeelightloftdj

/** Stateless to the caller, stateful only in local PCM. No microphones, files,
 * network, gain changes or synthetic frames. A 50%-overlapping FFT window
 * produces a real new onset observation every windowSize/2 samples.
 */
internal class OverlappingPcmWindows(private val windowSize:Int) {
    init { require(windowSize >= 512 && windowSize % 2 == 0) }
    private val previous=ShortArray(windowSize)
    private val half=windowSize / 2
    private var blocks=0

    fun accept(block:ShortArray):ShortArray? {
        require(block.size == half)
        if (blocks == 0) {
            block.copyInto(previous,0)
            blocks=1
            return null
        }
        if (blocks == 1) {
            block.copyInto(previous,half)
            blocks=2
            return previous.copyOf()
        }
        System.arraycopy(previous,half,previous,0,half)
        block.copyInto(previous,half)
        return previous.copyOf()
    }
}
