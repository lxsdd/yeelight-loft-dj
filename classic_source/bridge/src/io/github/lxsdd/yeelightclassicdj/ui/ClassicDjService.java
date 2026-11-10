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
public final class ClassicDjService extends Service {
    public static final String ACTION_MIC = "io.github.lxsdd.yeelightclassicdj.MIC";
    public static final String ACTION_STOP = "io.github.lxsdd.yeelightclassicdj.STOP";
    private static final String CHANNEL = "classic-loft-dj";
    private static final int NOTIFICATION_ID = 4812;
    private DjRuntime runtime;
    private final Object sessionGuard = new Object();
    private final ClassicSessionFence sessionFence = new ClassicSessionFence();
    private boolean shuttingDown;
    private long currentToken;
    private Thread startupWorker;
    private static volatile String currentState = "Bereit · ausgewählte Color4-Lampen";
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
            stopSession(0L, "Gestoppt", true);
            return START_NOT_STICKY;
        }
        if (!ACTION_MIC.equals(action)) return START_NOT_STICKY;
        if (Build.VERSION.SDK_INT >= 23 &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            currentState = "Mikrofonberechtigung fehlt";
            stopSelf();
            return START_NOT_STICKY;
        }
        final String[] selectedIps = intent.getStringArrayExtra(ClassicDjActivity.EXTRA_SELECTED_IPS);
        final java.util.List<String> ips = selectedIps == null ? java.util.Collections.emptyList()
            : java.util.Arrays.asList(selectedIps);
        if (ips.size() > 4 || new java.util.HashSet<>(ips).size() != ips.size()) {
            currentState = "Ungültige Lampenauswahl";
            stopSelf();
            return START_NOT_STICKY;
        }
        final DjRuntime session;
        final long token;
        synchronized (sessionGuard) {
            if (shuttingDown) return START_NOT_STICKY;
            if (sessionFence.owns(sessionFenceCurrent())) return START_NOT_STICKY;
            session = obtainExistingCheckpoint();
            if (session == null) {
                currentState = "Runtime fehlt · keine Lampensteuerung";
                stopSelf();
                return START_NOT_STICKY;
            }
            token = sessionFence.begin();
            if (token == 0L) return START_NOT_STICKY;
            currentToken = token;
            runtime = session;
            currentState = "Mikrofon-Analyse wird vorbereitet";
        }
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationManager nm = getSystemService(NotificationManager.class);
                if (nm != null) nm.createNotificationChannel(
                    new NotificationChannel(CHANNEL, "Loft DJ", NotificationManager.IMPORTANCE_LOW));
            }
            startForeground(NOTIFICATION_ID, notification());
            final DjRuntime.Listener callback = new DjRuntime.Listener() {
                @Override public void onState(String value) {
                    synchronized (sessionGuard) {
                        if (sessionFence.owns(token)) acceptState(value);
                    }
                }
                @Override public void onBeat(double bpm, float level) {
                    synchronized (sessionGuard) {
                        if (sessionFence.owns(token)) acceptBeat(bpm, level);
                    }
                }
                @Override public void onError(String error) {
                    Log.w("ClassicDJ", "Capture failure; session halted");
                    stopSession(token, "Mikrofonfehler · bitte Lampenstatus prüfen", true);
                }
            };
            Thread thread = new Thread(() -> {
                try {
                    session.start(this, "microphone", ips, callback);
                } catch (Throwable error) {
                    if (sessionFence.owns(token)) {
                        Log.e("ClassicDJ", "Music handshake or microphone start failed", error);
                        stopSession(token, "DJ-Start fehlgeschlagen; Lampenstatus prüfen", true);
                    }
                }
            }, "Classic-DJ-Session-Start");
            thread.setDaemon(true);
            synchronized (sessionGuard) {
                if (sessionFence.owns(token) && !shuttingDown) {
                    startupWorker = thread;
                    thread.start();
                }
            }
        } catch (Exception | LinkageError error) {
            Log.e("ClassicDJ", "Session start failed; stopping safely", error);
            stopSession(token, "DJ-Start fehlgeschlagen; Lampenstatus prüfen", true);
        }
        return START_NOT_STICKY;
    }

    // Caller owns sessionGuard. A token of zero must never be treated as live.
    private long sessionFenceCurrent() {
        return currentToken;
    }

    private Notification notification() {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        Intent reopen = new Intent(this, ClassicDjActivity.class);
        reopen.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP |
                        Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent open = PendingIntent.getActivity(this, 32, reopen, flags);
        Intent stop = new Intent(this, ClassicDjService.class);
        stop.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 33, stop, flags);
        Notification.Builder n = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return n.setContentTitle("Loft DJ · Classic")
            .setContentText("DJ läuft · Tippen öffnet Classic")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Stopp", stopPi)
            .build();
    }

    private void stopSession(long expectedToken, String finalState, boolean requestStopSelf) {
        final DjRuntime instance;
        synchronized (sessionGuard) {
            if (shuttingDown || (expectedToken != 0L && !sessionFence.owns(expectedToken))) return;
            shuttingDown = true;
            sessionFence.cancel(); // fence callbacks before touching any session/socket
            currentToken = 0L;
            instance = runtime;
            runtime = null;
            startupWorker = null;
            lastBpm = 0.0;
            lastLevel = 0f;
            currentState = finalState;
        }
        if (instance != null) {
            try { instance.stop(); }
            catch (Exception error) { Log.e("ClassicDJ", "Manual recovery may be needed", error); }
        }
        stopForeground(true);
        if (requestStopSelf) stopSelf();
    }

    @Override public void onDestroy() {
        stopSession(0L, "Gestoppt", false);
        super.onDestroy();
    }

    // Only called while holding sessionGuard for the confirmed current session.
    private void acceptState(String value) {
        if (value == null) return;
        if ("MIC_ANALYSIS_ONLY_NO_LAMPS".equals(value)) currentState = "Mikrofonanalyse · keine Lampensteuerung";
        else if (value.startsWith("DJ_ACTIVE_")) currentState = "Music-Rückkanal aktiv · " +
            value.substring(10).replace('_', ' ').toLowerCase(java.util.Locale.ROOT);
        else if ("STOPPED".equals(value)) currentState = "Gestoppt";
        Log.i("ClassicDJ", "Session state updated");
    }

    private void acceptBeat(double bpm, float level) {
        lastBpm = (!Double.isNaN(bpm) && !Double.isInfinite(bpm)) && bpm > 0 ? bpm : 0.0;
        lastLevel = (!Float.isNaN(level) && !Float.isInfinite(level))
            ? Math.max(0f, Math.min(level, 1f)) : 0f;
    }
}
