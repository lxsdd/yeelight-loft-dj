package android.media
object AudioFormat { const val CHANNEL_IN_MONO=16; const val ENCODING_PCM_16BIT=2 }
object MediaRecorder { object AudioSource { const val MIC=1; const val UNPROCESSED=9 } }
class AudioRecord(source:Int, rate:Int, channel:Int, encoding:Int, size:Int) {
    companion object {
        const val STATE_INITIALIZED=1; const val RECORDSTATE_RECORDING=3; const val READ_NON_BLOCKING=1
        var lastRate=0; var minimum=1024; var initialized=true; var starts=0; var stops=0; var releases=0; var reads=0
        fun getMinBufferSize(rate:Int, channel:Int, encoding:Int)=minimum
    }
    init { lastRate=rate }
    val state get()=if(initialized) STATE_INITIALIZED else 0
    var recordingState=0
    fun startRecording(){ starts++; recordingState=RECORDSTATE_RECORDING }
    fun read(buffer:ShortArray, offset:Int, size:Int, mode:Int):Int { check(mode==READ_NON_BLOCKING); reads++; return 0 }
    fun stop(){ stops++; recordingState=0 }
    fun release(){ releases++ }
}
