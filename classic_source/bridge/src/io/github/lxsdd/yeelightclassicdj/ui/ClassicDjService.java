package io.github.lxsdd.yeelightclassicdj.ui;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import io.github.lxsdd.yeelightclassicdj.runtime.DjRuntime;

/** One foreground owner for music. Never falls back to regular TCP-RGB on failure. */
public final class ClassicDjService extends Service implements DjRuntime.Listener {
    public static final String ACTION_MIC = "io.github.lxsdd.yeelightclassicdj.MIC";
    public static final String ACTION_STOP = "io.github.lxsdd.yeelightclassicdj.STOP";
    private static final String CHANNEL = "classic-loft-dj";
    private static final int NOTIFICATION_ID = 4812;
    private DjRuntime runtime;
    private boolean running;
    private static volatile String currentState = "Bereit · keine Lampensteuerung";
    private static volatile double lastBpm = 0.0;
    private static volatile float lastLevel = 0f;

    public static String uiStatus() {
        String bpm = lastBpm > 0.0 ? String.format(java.util.Locale.ROOT, "%.0f BPM", lastBpm) : "BPM wird analysiert";
        return currentState + " · " + bpm + " · Pegel " + Math.round(lastLevel * 100.0f) + "%";
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private DjRuntime obtainExistingCheckpoint() {
        // The private Kotlin checkpoint implements this adapter once compiled into sidecar dex.
        // Missing/changed implementation is NOT silently replaced by speculative LAN commands.
        try {
            Class<?> klass = Class.forName("io.github.lxsdd.yeelightclassicdj.runtime.CheckpointRuntime");
            return (DjRuntime) klass.getDeclaredConstructor().newInstance();
        } catch (Exception | LinkageError error) {
            Log.w("ClassicDJ", "Private checkpoint runtime not available");
            return null;
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? "" : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            finishSession();
            return START_NOT_STICKY;
        }
        if (!ACTION_MIC.equals(action)) return START_NOT_STICKY;
        if (running) return START_NOT_STICKY;
        currentState = "Mikrofon-Analyse wird vorbereitet";
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf();
            return START_NOT_STICKY;
        }
        runtime = obtainExistingCheckpoint();
        if (runtime == null) { currentState = "Runtime fehlt · keine Lampensteuerung"; stopSelf(); return START_NOT_STICKY; }
        try {
            // Required before a long-running AudioRecord session, app return PendingIntent is explicit.
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationManager nm = getSystemService(NotificationManager.class);
                if (nm != null) nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Loft DJ", NotificationManager.IMPORTANCE_LOW));
            }
            startForeground(NOTIFICATION_ID, notification());
            running = true; // Set first: a synchronous error callback must not be undone.
            runtime.start(this, "microphone", this);
        } catch (Exception error) {
            Log.e("ClassicDJ", "Session start failed; stopping safely", error);
            finishSession();
        }
        return START_NOT_STICKY;
    }

    private Notification notification() {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        Intent reopen = new Intent(this, ClassicDjActivity.class);
        reopen.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent open = PendingIntent.getActivity(this, 32, reopen, flags);
        Intent stop = new Intent(this, ClassicDjService.class);
        stop.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 33, stop, flags);
        Notification.Builder n = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return n.setContentTitle("Loft DJ · Classic")
            .setContentText("Mikrofon-Analyse (ohne Lampen) · Tippen öffnet die App")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Stopp", stopPi)
            .build();
    }

    private void finishSession() {
        currentState = "Gestoppt · keine Lampensteuerung";
        lastBpm = 0.0; lastLevel = 0f;
        DjRuntime instance = runtime;
        runtime = null;
        running = false;
        if (instance != null) {
            try { instance.stop(); }
            catch (Exception error) { Log.e("ClassicDJ", "Manual recovery may be needed", error); }
        }
        stopForeground(true);
        stopSelf();
    }
    @Override public void onDestroy() {
        finishSession();
        super.onDestroy();
    }
    @Override public void onState(String value) {
        if ("MIC_ANALYSIS_ONLY_NO_LAMPS".equals(value)) currentState = "Mikrofonanalyse · keine Lampensteuerung";
        else if ("STOPPED".equals(value)) currentState = "Gestoppt · keine Lampensteuerung";
        Log.i("ClassicDJ", "Session state updated");
    }
    @Override public void onBeat(double bpm, float level) {
        lastBpm = (!Double.isNaN(bpm) && !Double.isInfinite(bpm)) && bpm > 0 ? bpm : 0.0;
        lastLevel = (!Float.isNaN(level) && !Float.isInfinite(level)) ? Math.max(0f, Math.min(level, 1f)) : 0f;
    }
    @Override public void onError(String error) {
        Log.w("ClassicDJ", "Capture failure; session halted");
        finishSession();
        currentState = "Mikrofonfehler · keine Lampensteuerung";
    }
}
