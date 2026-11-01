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
    private var silentFrames = 0

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
        silentFrames = 0
        status = "Internes Audio freigegeben · auf abspielende App warten"
    }

    fun received(reading: MusicFrame) {
        if (!running) return
        frame = reading
        if (reading.rms < 0.004f) {
            silentFrames++
            if (silentFrames >= 40) status =
                "Kein erfassbarer Ton: App blockiert Capture, Audio läuft nur am Cast-Empfänger oder Stille"
        } else {
            silentFrames = 0
            status = "Interne Wiedergabe analysiert · Mikrofon nicht verwendet"
        }
        sink?.invoke(reading)
    }

    fun finished(message: String) {
        val active = running
        running = false
        frame = null
        silentFrames = 0
        status = message
        if (active) ended?.invoke()
    }
}
