package android.media
object AudioFormat {
    const val CHANNEL_IN_MONO=16
    const val ENCODING_PCM_16BIT=2
    class Builder {
        fun setEncoding(value:Int)=this
        fun setSampleRate(value:Int)=this
        fun setChannelMask(value:Int)=this
        fun build()=AudioFormat
    }
}
object AudioAttributes {
    const val USAGE_MEDIA=1
    const val USAGE_GAME=2
    const val USAGE_UNKNOWN=0
}
class AudioPlaybackCaptureConfiguration {
    class Builder(val projection:android.media.projection.MediaProjection) {
        fun addMatchingUsage(usage:Int)=this
        fun build()=AudioPlaybackCaptureConfiguration()
    }
}

object MediaRecorder { object AudioSource { const val MIC=1; const val UNPROCESSED=9 } }
class AudioRecord(private val source:Int, rate:Int, channel:Int, encoding:Int, size:Int) {
    class Builder {
        fun setAudioPlaybackCaptureConfig(config:AudioPlaybackCaptureConfiguration)=this
        fun setAudioFormat(format:AudioFormat)=this
        fun setBufferSizeInBytes(size:Int)=this
        fun build()=AudioRecord(0,16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,4096)
    }
    companion object {
        const val STATE_INITIALIZED=1; const val RECORDSTATE_RECORDING=3; const val READ_NON_BLOCKING=1
        var lastRate=0; var minimum=1024; var initialized=true; var starts=0; var stops=0; var releases=0; var reads=0
        var badInitializationSource = -1; var badStartSource = -1; var deniedSource = -1
        val createdSources = mutableListOf<Int>()
        fun getMinBufferSize(rate:Int, channel:Int, encoding:Int)=minimum
    }
    init { lastRate=rate; createdSources.add(source) }
    val state get()=if(initialized && source != badInitializationSource) STATE_INITIALIZED else 0
    var recordingState=0
    fun startRecording(){
        starts++
        if (source == deniedSource) throw SecurityException("microphone permission denied")
        if (source == badStartSource) throw IllegalStateException("microphone unavailable")
        recordingState=RECORDSTATE_RECORDING
    }
    fun read(buffer:ShortArray, offset:Int, size:Int, mode:Int):Int { check(mode==READ_NON_BLOCKING); reads++; return 0 }
    fun read(buffer:ShortArray, offset:Int, size:Int):Int { reads++; return 0 }
    fun getSampleRate():Int = lastRate
    fun stop(){ stops++; recordingState=0 }
    fun release(){ releases++ }
}
