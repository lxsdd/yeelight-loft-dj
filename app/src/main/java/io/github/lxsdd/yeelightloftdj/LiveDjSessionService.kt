package io.github.lxsdd.yeelightloftdj

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager

/** Explicit user-initiated session; never resumes automatically after kill. */
class LiveDjSessionService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            LoftControllerStore.current()?.let {
                it.stopColor4LiveDj()
                it.stopMicrophone()
                if (InternalAudioBridge.running) it.stopInternalAudio()
                if (!it.minimalProbeRunning) stopSelf()
            } ?: stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != START) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL,"Live-DJ",NotificationManager.IMPORTANCE_LOW))
            val stopIntent = Intent(this,LiveDjSessionService::class.java).apply { action = STOP }
            val stopAction = PendingIntent.getService(this,91,stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notice = Notification.Builder(this,CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("Yeelight Loft DJ · Live-DJ aktiv")
                .setContentText("Musiklicht läuft auch bei gesperrtem Bildschirm")
                .setOngoing(true)
                .addAction(Notification.Action.Builder(
                    android.R.drawable.ic_media_pause,"DJ stoppen",stopAction).build())
                .build()
            startForeground(91,notice,ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            active = true
            requested = false
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,"io.github.lxsdd.yeelightloftdj:LiveDJ"
            ).apply { setReferenceCounted(false); acquire() }
        } catch (_: Exception) {
            active = false
            requested = false
            LoftControllerStore.current()?.stopColor4LiveDj()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        active = false
        requested = false
        runCatching { wakeLock?.let { if (it.isHeld) it.release() } }
        wakeLock = null
        LoftControllerStore.current()?.let {
            if (it.color4LiveDjRunning) {
                it.stopColor4LiveDj()
                it.stopMicrophone()
            }
        }
        LoftControllerStore.serviceFinished()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "loft-live-dj-microphone"
        private const val START = "io.github.lxsdd.yeelightloftdj.LIVE_START"
        private const val STOP = "io.github.lxsdd.yeelightloftdj.LIVE_STOP"
        @Volatile private var requested = false
        @Volatile private var active = false
        fun isHolding() = requested || active

        fun start(context: Context) {
            if (isHolding()) return
            requested = true
            try {
                context.startForegroundService(
                    Intent(context,LiveDjSessionService::class.java).apply { action = START })
            } catch (failure: Exception) {
                requested = false
                throw failure
            }
        }
        fun finish(context: Context) {
            requested = false
            context.stopService(Intent(context,LiveDjSessionService::class.java))
        }
    }
}
