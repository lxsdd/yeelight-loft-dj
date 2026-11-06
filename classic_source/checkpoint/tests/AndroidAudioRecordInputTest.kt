package loftdj.classic
import android.media.AudioRecord
import android.media.MediaRecorder
fun main() {
    check(runCatching { ClassicAndroidAudioRecordInput.open(source=3) }.isFailure)
    check(runCatching { ClassicAndroidAudioRecordInput.open(sampleRate=1000) }.isFailure)
    AudioRecord.minimum=-1
    check(runCatching { ClassicAndroidAudioRecordInput.open() }.isFailure)
    AudioRecord.minimum=1024; AudioRecord.initialized=false
    check(runCatching { ClassicAndroidAudioRecordInput.open() }.isFailure)
    check(AudioRecord.releases==1)
    AudioRecord.initialized=true
    val input=ClassicAndroidAudioRecordInput.open(source=MediaRecorder.AudioSource.MIC)
    check(AudioRecord.lastRate==16000) // FFT and capture must share the default sample rate
    check(input.read(ShortArray(64))==0 && AudioRecord.reads==1)
    input.close(); input.close()
    check(AudioRecord.stops==1 && AudioRecord.releases==2)
    check(runCatching { input.read(ShortArray(64)) }.isFailure)
    println("PASS Android AudioRecord adapter contract (stubbed platform)")
}
