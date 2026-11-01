package io.github.lxsdd.yeelightloftdj

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Process-local UI bridge: PCM never leaves the device or persists to files. */
object InternalAudioBridge {
    var running by mutableStateOf(false)
        private set
    var status by mutableStateOf("Interner Audiomodus aus")
        private set
    var frame by mutableStateOf<MusicFrame?>(null)
        private set

    private var sink: ((MusicFrame) -> Unit)? = null
    private var ended: (() -> Unit)? = null
    private val signalWatch = PlaybackSignalWatch()

    fun attach(receiver: (MusicFrame) -> Unit, onEnded: () -> Unit) {
        sink = receiver
        ended = onEnded
    }

    fun detach(receiver: (MusicFrame) -> Unit) {
        if (sink === receiver) {
            sink = null
            ended = null
        }
    }

    fun started() {
        running = true
        frame = null
        signalWatch.reset()
        status = "Internes Audio freigegeben · auf abspielende App warten"
    }

    fun received(reading: MusicFrame) {
        if (!running) return
        frame = reading
        status = when (signalWatch.observe(reading.rms, reading.timeMs)) {
            PlaybackSignalState.AUDIBLE ->
                "Internes Audiosignal erkannt · reines digitales PCM"
            PlaybackSignalState.QUIET ->
                "Capture gestartet · auf abspielbare Audioquelle warten"
            PlaybackSignalState.NO_SIGNAL ->
                "Kein Audiosignal: Stille, Cast-Empfänger oder Aufnahme durch Quell-App gesperrt"
        }
        sink?.invoke(reading)
    }

    fun finished(message: String) {
        val active = running
        running = false
        frame = null
        signalWatch.reset()
        status = message
        if (active) ended?.invoke()
    }
}
