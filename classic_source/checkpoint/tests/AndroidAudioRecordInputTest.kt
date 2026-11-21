package loftdj.classic
import android.media.AudioRecord
import android.media.MediaRecorder
fun main() {
    check(runCatching { ClassicAndroidAudioRecordInput.open(source=3) }.isFailure)
    check(runCatching { ClassicAndroidAudioRecordInput.open(sampleRate=1000) }.isFailure)
    AudioRecord.minimum = -1
    check(runCatching { ClassicAndroidAudioRecordInput.open() }.isFailure)
    check(AudioRecord.createdSources.isEmpty()) // unsupported format: no constructor
    AudioRecord.minimum = 1024

    // Both Android capture sources fail initialization: exactly two attempts,
    // each released once; an explicit UNPROCESSED request never falls back.
    AudioRecord.initialized = false
    check(runCatching { ClassicAndroidAudioRecordInput.open() }.isFailure)
    check(AudioRecord.createdSources ==
        listOf(MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.UNPROCESSED))
    check(AudioRecord.releases == 2)
    AudioRecord.createdSources.clear()
    check(runCatching {
        ClassicAndroidAudioRecordInput.open(source=MediaRecorder.AudioSource.UNPROCESSED)
    }.isFailure)
    check(AudioRecord.createdSources == listOf(MediaRecorder.AudioSource.UNPROCESSED))
    check(AudioRecord.releases == 3)
    AudioRecord.initialized = true
    AudioRecord.createdSources.clear()

    val input = ClassicAndroidAudioRecordInput.open(source=MediaRecorder.AudioSource.MIC)
    check(AudioRecord.lastRate == 16000)
    check(input.read(ShortArray(64)) == 0 && AudioRecord.reads == 1)
    input.close(); input.close()
    check(AudioRecord.stops == 1 && AudioRecord.releases == 4)
    check(runCatching { input.read(ShortArray(64)) }.isFailure)

    // Real source-specific initialization failure uses a bounded second
    // source; the first recorder is closed before the fallback is created.
    AudioRecord.createdSources.clear()
    AudioRecord.badInitializationSource = MediaRecorder.AudioSource.MIC
    val fallback = ClassicAndroidAudioRecordInput.open()
    check(AudioRecord.createdSources ==
        listOf(MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.UNPROCESSED))
    check(AudioRecord.releases == 5)
    fallback.close()
    check(AudioRecord.releases == 6 && AudioRecord.stops == 2)
    AudioRecord.badInitializationSource = -1

    // A failed first start also qualifies for state-only fallback, not
    // permission bypass. No two AudioRecords are ever left recording.
    AudioRecord.createdSources.clear()
    AudioRecord.badStartSource = MediaRecorder.AudioSource.MIC
    val fromStart = ClassicAndroidAudioRecordInput.open()
    check(AudioRecord.createdSources ==
        listOf(MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.UNPROCESSED))
    fromStart.close()
    check(AudioRecord.releases == 8 && AudioRecord.stops == 3)
    AudioRecord.badStartSource = -1

    AudioRecord.createdSources.clear()
    AudioRecord.deniedSource = MediaRecorder.AudioSource.MIC
    val permission = runCatching { ClassicAndroidAudioRecordInput.open() }.exceptionOrNull()
    check(permission is SecurityException)
    check(AudioRecord.createdSources == listOf(MediaRecorder.AudioSource.MIC))
    check(AudioRecord.releases == 9) // no alternative source was attempted
    AudioRecord.deniedSource = -1

    AudioRecord.createdSources.clear()
    val clean = ClassicAndroidAudioRecordInput.open()
    check(AudioRecord.createdSources == listOf(MediaRecorder.AudioSource.MIC))
    clean.close()
    check(AudioRecord.releases == 10 && AudioRecord.stops == 4)
    println("PASS Android AudioRecord state-only fallback, permission denial, cleanup and idempotence")
}
