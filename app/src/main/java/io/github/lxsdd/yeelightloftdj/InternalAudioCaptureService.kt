package io.github.lxsdd.yeelightloftdj

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android 10+ playback capture with explicit user consent. Runs as a visible
 * foreground media-projection service so other apps can be foregrounded.
 * Never captures screens, never writes audio, never bypasses source policies.
 */
class InternalAudioCaptureService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val closing = AtomicBoolean(false)
    private var projection: MediaProjection? = null
    private var recorder: AudioRecord? = null
    private var worker: Job? = null
    private var callback: MediaProjection.Callback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCapture("Interner Audiomodus beendet")
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START || worker != null) return START_NOT_STICKY
        try {
            val resultCode = intent.getIntExtra(EXTRA_CODE, Int.MIN_VALUE)
            @Suppress("DEPRECATION")
            val data = if (android.os.Build.VERSION.SDK_INT >= 33)
                intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
            else intent.getParcelableExtra<Intent>(EXTRA_DATA)
            data ?: error("Android-Freigabe fehlt")
            require(resultCode == android.app.Activity.RESULT_OK) { "Freigabe nicht erteilt" }
            val notifications = getSystemService(NotificationManager::class.java)
            notifications.createNotificationChannel(NotificationChannel(CHANNEL,
                "Interne Audioanalyse", NotificationManager.IMPORTANCE_LOW))
            val stop = Intent(this, InternalAudioCaptureService::class.java).apply {
                action = ACTION_STOP
            }
            val stopPending = PendingIntent.getService(this, 72, stop,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notice = Notification.Builder(this, CHANNEL)
                .setContentTitle("Yeelight Loft DJ · internes Audio")
                .setContentText("Analysiert erlaubte Wiedergabe anderer Apps")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .addAction(Notification.Action.Builder(
                    android.R.drawable.ic_media_pause,
                    "Analyse stoppen", stopPending).build())
                .build()
            startForeground(NOTIFICATION_ID, notice,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val media = manager.getMediaProjection(resultCode, data)
                ?: error("Android hat die Audiofreigabe zurückgewiesen")
            projection = media
            val listener = object : MediaProjection.Callback() {
                override fun onStop() {
                    stopCapture("Android hat die Audiofreigabe beendet")
                }
            }
            callback = listener
            media.registerCallback(listener, Handler(Looper.getMainLooper()))
            InternalAudioBridge.started()
            worker = scope.launch {
                try {
                    capture(media)
                } catch (ex: Exception) {
                    withContext(Dispatchers.Main.immediate) {
                        InternalAudioBridge.finished(
                            "Interne Audioanalyse fehlgeschlagen: " +
                                (ex.message ?: ex.javaClass.simpleName))
                    }
                } finally {
                    withContext(Dispatchers.Main.immediate) {
                        stopCapture("Interner Audiomodus beendet")
                    }
                }
            }
        } catch (e: Exception) {
            stopCapture("Audiomodus konnte nicht starten: " +
                (e.message ?: e.javaClass.simpleName))
        }
        return START_NOT_STICKY
    }

    private suspend fun capture(media: MediaProjection) = withContext(Dispatchers.IO) {
        val rate = 16000
        // 1,024 PCM samples at 16 kHz = 64 ms per analysis window.
        // This decreases frame acquisition time without increasing the
        // guarded reverse-channel or ordinary LAN command rate.
        val size = 1024
        val config = AudioPlaybackCaptureConfiguration.Builder(media)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val minimum = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "16-kHz-Audio-Capture wird nicht unterstützt" }
        val audio = AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(config)
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(rate)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build())
            .setBufferSizeInBytes(maxOf(minimum * 2, size * 8))
            .build()
        recorder = audio
        check(audio.state == AudioRecord.STATE_INITIALIZED) {
            "Android konnte keinen Playback-Capture-Kanal bereitstellen"
        }
        val analyzer = MusicAnalyzer(rate, size)
        try {
            audio.startRecording()
            check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "Playback-Capture nicht gestartet"
            }
            val pcm = ShortArray(size)
            while (!closing.get()) {
                var filled = 0
                while (!closing.get() && filled < size) {
                    val n = audio.read(pcm, filled, size - filled, AudioRecord.READ_BLOCKING)
                    if (n < 0) {
                        if (closing.get()) return@withContext
                        error("AudioRecord: Lesefehler $n")
                    }
                    if (n == 0) continue
                    filled += n
                }
                if (filled == size && !closing.get()) {
                    val frame = analyzer.analyze(pcm, SystemClock.elapsedRealtime())
                    withContext(Dispatchers.Main.immediate) {
                        InternalAudioBridge.received(frame)
                    }
                }
            }
        } finally {
            recorder = null
            runCatching {
                if (audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) audio.stop()
            }
            audio.release()
        }
    }

    private fun stopCapture(reason: String) {
        if (!closing.compareAndSet(false, true)) return
        // Stop the blocking read; the worker owns AudioRecord.release().
        runCatching { recorder?.stop() }
        callback?.let { cb ->
            runCatching { projection?.unregisterCallback(cb) }
        }
        callback = null
        runCatching { projection?.stop() }
        projection = null
        InternalAudioBridge.finished(reason)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopCapture("Interne Audioanalyse beendet")
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "internal-audio-playback"
        private const val NOTIFICATION_ID = 71
        private const val ACTION_START = "io.github.lxsdd.yeelightloftdj.AUDIO_START"
        private const val ACTION_STOP = "io.github.lxsdd.yeelightloftdj.AUDIO_STOP"
        private const val EXTRA_CODE = "consent-code"
        private const val EXTRA_DATA = "consent-data"

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, InternalAudioCaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_CODE, resultCode)
                putExtra(EXTRA_DATA, data)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, InternalAudioCaptureService::class.java).apply {
                action = ACTION_STOP
            })
        }
    }
}
